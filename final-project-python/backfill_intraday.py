import argparse
import time
from datetime import datetime, timezone, timedelta

import pandas as pd
import pandas_market_calendars as mcal
import yfinance as yf

from backfill_config import API_MAX_DAYS, MAIN_TABLE_RETENTION_DAYS
from backfill_utils import (
    get_engine,
    ensure_schema,
    yahoo_symbol,
    sleep_backoff,
    to_epoch_sec,
    insert_stock_ohlc,
    parse_symbols_arg,
    utc_now,
)

NYSE = mcal.get_calendar("NYSE")

# Fallback rule requested:
# full-window fails -> keep latest 60% window -> split into 2 half-batches
FALLBACK_KEEP_RATIO = 0.60


def last_trading_schedule(n_sessions: int, lookback_days: int = 180) -> pd.DataFrame:
    end = pd.Timestamp.now("UTC")
    start = end - pd.Timedelta(days=lookback_days)
    sched = NYSE.schedule(start_date=start, end_date=end)
    return sched.tail(n_sessions)


def flatten_yf_columns(df: pd.DataFrame) -> pd.DataFrame:
    if isinstance(df.columns, pd.MultiIndex):
        df = df.copy()
        df.columns = [c[0] if isinstance(c, tuple) else c for c in df.columns]
    return df


def fetch_intraday_window(
    symbol: str,
    interval: str,
    start_dt: datetime,
    end_dt: datetime,
    filter_ts_from: int | None = None,
    filter_ts_to: int | None = None,
):
    sym_fetch = yahoo_symbol(symbol)
    df = yf.download(sym_fetch, interval=interval, start=start_dt, end=end_dt, progress=False)

    if df is None or df.empty:
        return None

    df = flatten_yf_columns(df).reset_index()
    dt_col = df.columns[0]

    df["ts"] = to_epoch_sec(df[dt_col])
    df["symbol"] = symbol
    df["data_type"] = interval

    df = df.rename(
        columns={
            "Open": "open",
            "High": "high",
            "Low": "low",
            "Close": "close",
            "Volume": "volume",
        }
    )
    df = df[["symbol", "data_type", "ts", "open", "high", "low", "close", "volume"]]
    df = df.dropna(subset=["open", "high", "low", "close"])

    if filter_ts_from is not None and filter_ts_to is not None:
        df = df[(df["ts"] >= filter_ts_from) & (df["ts"] < filter_ts_to)]

    return None if df.empty else df


def download_and_insert_with_retries(
    engine,
    symbol: str,
    interval: str,
    start_dt: datetime,
    end_dt: datetime,
    max_attempts: int,
    label: str,
) -> tuple[bool, int]:
    for attempt in range(max_attempts):
        try:
            df = fetch_intraday_window(symbol, interval, start_dt, end_dt)
            with engine.begin() as conn:
                inserted = insert_stock_ohlc(conn, df)
            return True, inserted
        except Exception as e:
            print(
                f"[intraday {label}] {symbol} interval={interval} "
                f"{start_dt.date()}..{end_dt.date()} "
                f"attempt={attempt+1}/{max_attempts} error={e}"
            )
            if attempt < max_attempts - 1:
                sleep_backoff(attempt)

    return False, 0


def run(
    symbols: list[str],
    mode: str,
    intervals: list[str],
    max_attempts: int,
    sleep_sec: float,
    sessions_override: int | None,
    clamp_1m_calendar_days: int,
):
    if not symbols:
        raise ValueError("No symbols provided. Use --symbols or --symbols-file.")

    engine = get_engine()
    ensure_schema(engine)
    total = 0

    for interval in intervals:
        if interval == "1d":
            continue

        if sessions_override is not None:
            sessions = int(sessions_override)
        else:
            sessions = int(MAIN_TABLE_RETENTION_DAYS.get(interval, 7))

        sched = last_trading_schedule(sessions)
        if sched is None or sched.empty:
            print(f"[intraday {mode}] interval={interval} no trading schedule found, skip")
            continue

        open_ts = int(pd.Timestamp(sched.iloc[0]["market_open"]).tz_convert("UTC").timestamp())
        close_ts = int(pd.Timestamp(sched.iloc[-1]["market_close"]).tz_convert("UTC").timestamp())

        start_dt = datetime.fromtimestamp(open_ts, tz=timezone.utc)
        end_dt = datetime.fromtimestamp(close_ts, tz=timezone.utc)

        # Yahoo 1m only allows roughly last 30 calendar days. Clamp to be safe.
        if interval == "1m":
            min_start = utc_now() - timedelta(days=int(clamp_1m_calendar_days))
            if start_dt < min_start:
                start_dt = min_start

        print(
            f"[intraday {mode}] interval={interval} sessions={sessions} "
            f"window={start_dt.isoformat()}..{end_dt.isoformat()}"
        )

        for sym in symbols:
            sym = sym.strip()
            if not sym:
                continue

            # 1) Try one full-window request first
            ok_full, inserted_full = download_and_insert_with_retries(
                engine=engine,
                symbol=sym,
                interval=interval,
                start_dt=start_dt,
                end_dt=end_dt,
                max_attempts=max_attempts,
                label=f"{mode} full",
            )

            if ok_full:
                total += inserted_full
                print(f"[intraday {mode}] {sym} interval={interval} full-window inserted={inserted_full}")
            else:
                # 2) Fallback: keep latest 60% window, then split into 2 half-batches
                full_span = end_dt - start_dt
                reduced_start = end_dt - (full_span * FALLBACK_KEEP_RATIO)
                if reduced_start < start_dt:
                    reduced_start = start_dt

                mid = reduced_start + (end_dt - reduced_start) / 2

                print(
                    f"[intraday {mode}] {sym} interval={interval} full-window failed. "
                    f"Fallback -> keep 60% window and split into 2 batches:\n"
                    f"  batch1: {reduced_start.isoformat()} .. {mid.isoformat()}\n"
                    f"  batch2: {mid.isoformat()} .. {end_dt.isoformat()}\n"
                    f"  hint: consider lowering API_MAX_DAYS['{interval}'] "
                    f"(current={API_MAX_DAYS.get(interval, 'N/A')}) in backfill_config.py"
                )

                ok1, inserted1 = download_and_insert_with_retries(
                    engine=engine,
                    symbol=sym,
                    interval=interval,
                    start_dt=reduced_start,
                    end_dt=mid,
                    max_attempts=max_attempts,
                    label=f"{mode} fallback-1",
                )

                ok2, inserted2 = download_and_insert_with_retries(
                    engine=engine,
                    symbol=sym,
                    interval=interval,
                    start_dt=mid,
                    end_dt=end_dt,
                    max_attempts=max_attempts,
                    label=f"{mode} fallback-2",
                )

                total += inserted1 + inserted2

                if not (ok1 and ok2):
                    print(
                        f"[intraday {mode}] {sym} interval={interval} fallback still has failures. "
                        f"Please reduce API_MAX_DAYS['{interval}'] and rerun."
                    )
                else:
                    print(
                        f"[intraday {mode}] {sym} interval={interval} fallback inserted={inserted1 + inserted2}"
                    )

            if sleep_sec:
                time.sleep(float(sleep_sec))

            print(f"[intraday {mode}] {sym} interval={interval} done")

    engine.dispose()
    print(f"[intraday {mode}] Total inserted: {total}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["initial", "missing"], required=True)
    ap.add_argument("--symbols", default=None)
    ap.add_argument("--symbols-file", default=None)
    ap.add_argument("--intervals", default="all", help="all or comma list: 1m,5m,15m,30m,1h,4h")
    ap.add_argument("--max-attempts", type=int, default=3)
    ap.add_argument("--sleep", type=float, default=0.1)
    ap.add_argument("--sessions", type=int, default=None, help="Override trading sessions window for all intervals")
    ap.add_argument("--clamp-1m-calendar-days", type=int, default=30)
    args = ap.parse_args()

    syms = parse_symbols_arg(args.symbols, args.symbols_file)

    if args.intervals.strip().lower() == "all":
        intervals = [k for k in MAIN_TABLE_RETENTION_DAYS.keys() if k != "1d"]
    else:
        intervals = [s.strip() for s in args.intervals.split(",") if s.strip()]

    run(
        symbols=syms,
        mode=args.mode,
        intervals=intervals,
        max_attempts=args.max_attempts,
        sleep_sec=args.sleep,
        sessions_override=args.sessions,
        clamp_1m_calendar_days=args.clamp_1m_calendar_days,
    )


if __name__ == "__main__":
    main()
