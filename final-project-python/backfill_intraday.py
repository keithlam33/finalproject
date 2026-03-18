import argparse
import time
from datetime import datetime, timedelta, timezone

import pandas as pd
import pandas_market_calendars as mcal
import yfinance as yf
from sqlalchemy import text

from backfill_config import API_MAX_DAYS
from backfill_utils import (
    ensure_schema,
    get_engine,
    insert_stock_ohlc,
    parse_symbols_arg,
    sleep_backoff,
    to_epoch_sec,
    utc_now,
    yahoo_symbol,
)

# Fallback rule requested:
# full-window fails -> keep latest 60% window -> split into 2 half-batches
FALLBACK_KEEP_RATIO = 0.60
NYSE = mcal.get_calendar("NYSE")
PROGRESS_EVERY = 20
INTERVAL_SECONDS = {
    "1m": 60,
    "5m": 5 * 60,
    "15m": 15 * 60,
    "30m": 30 * 60,
    "1h": 60 * 60,
    "4h": 4 * 60 * 60,
}


def log(message: str) -> None:
    print(message, flush=True)


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

    valid_ts = df["ts"].dropna()
    if valid_ts.empty:
        return None

    # Guardrail: intraday timestamps should be modern epoch seconds, not 0/1.
    # If conversion collapses to tiny values, abort the batch instead of writing garbage.
    if int(valid_ts.max()) < 1_000_000_000:
        raise ValueError(
            f"Invalid intraday ts detected for {symbol} {interval}: "
            f"min_ts={int(valid_ts.min())} max_ts={int(valid_ts.max())}"
        )

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
            if df is None or df.empty:
                raise ValueError("No intraday rows returned from yfinance")
            with engine.begin() as conn:
                inserted = insert_stock_ohlc(conn, df)
            return True, inserted
        except Exception as e:
            log(
                f"[intraday {label}] {symbol} interval={interval} "
                f"{start_dt.date()}..{end_dt.date()} "
                f"attempt={attempt+1}/{max_attempts} error={e}"
            )
            if attempt < max_attempts - 1:
                sleep_backoff(attempt)

    return False, 0


def resolve_fetch_end(now_utc: datetime) -> datetime:
    # Keep intraday fetch simple:
    # during regular NYSE session -> use now
    # outside regular session -> use latest NYSE close
    now_ts = pd.Timestamp(now_utc)
    sched = NYSE.schedule(
        start_date=(now_ts - pd.Timedelta(days=7)).date(),
        end_date=(now_ts + pd.Timedelta(days=1)).date(),
    )
    if sched.empty:
        return now_utc

    is_regular_session = (
        ((sched["market_open"] <= now_ts) & (now_ts <= sched["market_close"])).any()
    )
    if is_regular_session:
        return now_utc

    closed_market_close = sched.loc[sched["market_close"] <= now_ts, "market_close"]
    if closed_market_close.empty:
        return now_utc

    last_close = pd.Timestamp(closed_market_close.iloc[-1]).tz_convert("UTC")
    return last_close.to_pydatetime()


def resolve_fetch_window(interval: str) -> tuple[datetime, datetime]:
    max_api_days = API_MAX_DAYS.get(interval)
    if not max_api_days:
        raise ValueError(f"Unsupported intraday interval: {interval}")

    max_api_days = int(max_api_days)
    end_dt = resolve_fetch_end(utc_now())
    start_dt = end_dt - timedelta(days=max_api_days)
    return start_dt, end_dt


def load_latest_ts_map(engine, interval: str) -> dict[str, int]:
    with engine.begin() as conn:
        rows = conn.execute(
            text(
                """
SELECT symbol, MAX(ts) AS max_ts
FROM stock_ohlc
WHERE data_type = :interval
GROUP BY symbol
"""
            ),
            {"interval": interval},
        ).fetchall()
    return {str(r[0]): int(r[1]) for r in rows if r[0] is not None and r[1] is not None}


def resolve_symbol_window(
    interval: str,
    base_start_dt: datetime,
    end_dt: datetime,
    latest_ts: int | None,
) -> tuple[datetime | None, datetime, str]:
    if latest_ts is None:
        return base_start_dt, end_dt, "full"

    interval_sec = INTERVAL_SECONDS.get(interval, 60)
    latest_dt = datetime.fromtimestamp(latest_ts, tz=timezone.utc)
    if latest_dt < base_start_dt:
        return base_start_dt, end_dt, "full"

    # If latest row is already close to the latest expected bar, skip refetch.
    if latest_dt >= end_dt - timedelta(seconds=interval_sec * 2):
        return None, end_dt, "skip"

    overlap = timedelta(seconds=interval_sec * 2)
    start_dt = max(base_start_dt, latest_dt - overlap)
    if start_dt >= end_dt:
        return None, end_dt, "skip"

    return start_dt, end_dt, "tail"


def run(
    symbols: list[str],
    mode: str,
    intervals: list[str],
    max_attempts: int,
    sleep_sec: float,
):
    engine = get_engine()
    log(
        f"[intraday {mode}] start. "
        f"intervals={','.join(intervals)} symbols={'db-active' if not symbols else len(symbols)}"
    )
    ensure_schema(engine)
    total = 0

    if not symbols:
        with engine.begin() as conn:
            rows = conn.execute(
                text(
                    """
SELECT symbol
FROM stocks
WHERE status = true
ORDER BY symbol
"""
                )
            ).fetchall()
        symbols = [r[0] for r in rows]

    if not symbols:
        engine.dispose()
        raise ValueError("No symbols provided and no active symbols found in DB.")

    for interval in intervals:
        if interval == "1d":
            continue

        start_dt, end_dt = resolve_fetch_window(interval=interval)
        fetch_days = max(1, int((end_dt - start_dt).total_seconds() // 86400))

        log(
            f"[intraday {mode}] interval={interval} api_days={fetch_days} "
            f"window={start_dt.isoformat()}..{end_dt.isoformat()}"
        )

        log(f"[intraday {mode}] interval={interval} loading latest DB ts map...")
        latest_ts_map = load_latest_ts_map(engine, interval)
        log(
            f"[intraday {mode}] interval={interval} latest DB ts loaded for {len(latest_ts_map)} symbols"
        )
        success_symbols = 0
        skipped_symbols = 0
        failed_symbols = 0
        inserted_rows = 0

        for idx, sym in enumerate(symbols, start=1):
            sym = sym.strip()
            if not sym:
                continue

            if mode == "initial":
                request_start_dt, request_end_dt, request_kind = start_dt, end_dt, "full"
            else:
                request_start_dt, request_end_dt, request_kind = resolve_symbol_window(
                    interval=interval,
                    base_start_dt=start_dt,
                    end_dt=end_dt,
                    latest_ts=latest_ts_map.get(sym),
                )

            if request_kind == "skip" or request_start_dt is None:
                skipped_symbols += 1
                continue

            # 1) Try one full-window request first
            ok_full, inserted_full = download_and_insert_with_retries(
                engine=engine,
                symbol=sym,
                interval=interval,
                start_dt=request_start_dt,
                end_dt=request_end_dt,
                max_attempts=max_attempts,
                label=f"{mode} {request_kind}",
            )

            if ok_full:
                total += inserted_full
                inserted_rows += inserted_full
                success_symbols += 1
            else:
                # 2) Fallback: keep latest 60% window, then split into 2 half-batches
                full_span = request_end_dt - request_start_dt
                reduced_start = request_end_dt - (full_span * FALLBACK_KEEP_RATIO)
                if reduced_start < request_start_dt:
                    reduced_start = request_start_dt

                mid = reduced_start + (request_end_dt - reduced_start) / 2

                log(
                    f"[intraday {mode}] {sym} interval={interval} {request_kind}-window failed. "
                    f"Fallback -> keep 60% window and split into 2 batches:\n"
                    f"  batch1: {reduced_start.isoformat()} .. {mid.isoformat()}\n"
                    f"  batch2: {mid.isoformat()} .. {request_end_dt.isoformat()}\n"
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
                    end_dt=request_end_dt,
                    max_attempts=max_attempts,
                    label=f"{mode} fallback-2",
                )

                total += inserted1 + inserted2
                inserted_rows += inserted1 + inserted2

                if not (ok1 and ok2):
                    failed_symbols += 1
                    log(
                        f"[intraday {mode}] {sym} interval={interval} fallback still has failures. "
                        f"Please reduce API_MAX_DAYS['{interval}'] and rerun."
                    )
                else:
                    success_symbols += 1
                    log(
                        f"[intraday {mode}] {sym} interval={interval} fallback inserted={inserted1 + inserted2}"
                    )

            if sleep_sec:
                time.sleep(float(sleep_sec))

            if idx % PROGRESS_EVERY == 0 or idx == len(symbols):
                log(
                    f"[intraday {mode}] interval={interval} progress {idx}/{len(symbols)} "
                    f"success={success_symbols} skipped={skipped_symbols} "
                    f"failed={failed_symbols} inserted_rows={inserted_rows}"
                )

        log(
            f"[intraday {mode}] interval={interval} done. "
            f"success={success_symbols} skipped={skipped_symbols} failed={failed_symbols} inserted_rows={inserted_rows}"
        )

    engine.dispose()
    log(f"[intraday {mode}] Total inserted: {total}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["initial", "missing"], required=True)
    ap.add_argument("--symbols", default=None)
    ap.add_argument("--symbols-file", default=None)
    ap.add_argument("--intervals", default="all", help="all or comma list: 1m,5m,15m,30m,1h,4h")
    ap.add_argument("--max-attempts", type=int, default=3)
    ap.add_argument("--sleep", type=float, default=0.0)
    args = ap.parse_args()

    syms = parse_symbols_arg(args.symbols, args.symbols_file)

    if args.intervals.strip().lower() == "all":
        intervals = [k for k in API_MAX_DAYS.keys() if k != "1d"]
    else:
        intervals = [s.strip() for s in args.intervals.split(",") if s.strip()]

    run(
        symbols=syms,
        mode=args.mode,
        intervals=intervals,
        max_attempts=args.max_attempts,
        sleep_sec=args.sleep,
    )


if __name__ == "__main__":
    main()
