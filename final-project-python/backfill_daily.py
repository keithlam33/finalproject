import argparse
import time
from datetime import date, datetime, timedelta, timezone

import pandas as pd
import pandas_market_calendars as mcal
import requests
from sqlalchemy import text

from backfill_utils import (
    get_engine,
    ensure_schema,
    yahoo_symbol,
    sleep_backoff,
    insert_stock_ohlc,
    parse_symbols_arg,
)


NYSE = mcal.get_calendar("NYSE")
PROGRESS_EVERY = 20


def log(message: str) -> None:
    print(message, flush=True)


def normalize_daily_ts_to_session_start_utc(ts_series: pd.Series) -> pd.Series:
    raw_utc = pd.to_datetime(ts_series, unit="s", utc=True, errors="coerce")
    trade_dates = raw_utc.dt.strftime("%Y-%m-%d")
    session_open_ny = pd.to_datetime(trade_dates + " 09:30:00").dt.tz_localize("America/New_York")
    session_open_utc = session_open_ny.dt.tz_convert("UTC")
    return session_open_utc.map(lambda x: int(x.timestamp()) if pd.notna(x) else pd.NA).astype("Int64")


def last_trading_schedule(n_sessions: int, lookback_days: int = 365) -> pd.DataFrame:
    end = pd.Timestamp.now("UTC")
    start = end - pd.Timedelta(days=lookback_days)
    sched = NYSE.schedule(start_date=start, end_date=end)
    return sched.tail(n_sessions)


def resolve_daily_end_date() -> date:
    now_ts = pd.Timestamp.now("UTC")
    sched = NYSE.schedule(
        start_date=(now_ts - pd.Timedelta(days=10)).date(),
        end_date=(now_ts + pd.Timedelta(days=1)).date(),
    )
    closed = sched.loc[sched["market_close"] <= now_ts]
    if closed.empty:
        return (now_ts - pd.Timedelta(days=1)).date()
    return closed.index[-1].date()


def load_latest_daily_ts_map(engine) -> dict[str, int]:
    with engine.begin() as conn:
        rows = conn.execute(
            text(
                """
SELECT symbol, MAX(ts) AS max_ts
FROM stock_ohlc
WHERE data_type = '1d'
GROUP BY symbol
"""
            )
        ).fetchall()
    return {str(r[0]): int(r[1]) for r in rows if r[0] is not None and r[1] is not None}


def fetch_daily_yahoo_chart(symbol: str, start_date: date, end_date: date):
    sym_fetch = yahoo_symbol(symbol)

    start_dt = datetime(start_date.year, start_date.month, start_date.day, tzinfo=timezone.utc)
    end_dt = datetime(end_date.year, end_date.month, end_date.day, tzinfo=timezone.utc) + timedelta(days=1)

    period1 = int(start_dt.timestamp())
    period2 = int(end_dt.timestamp())

    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym_fetch}"
    params = {
        "period1": period1,
        "period2": period2,
        "interval": "1d",
        "events": "history",
        "includeAdjustedClose": "true",
    }
    headers = {"User-Agent": "Mozilla/5.0"}

    resp = requests.get(url, params=params, headers=headers, timeout=20)
    resp.raise_for_status()
    data = resp.json()

    if not data.get("chart") or not data["chart"].get("result"):
        return None

    result = data["chart"]["result"][0]
    timestamps = result.get("timestamp", [])
    if not timestamps:
        return None

    quote = result["indicators"]["quote"][0]
    ts = normalize_daily_ts_to_session_start_utc(pd.Series(timestamps, dtype="int64"))

    df = pd.DataFrame(
        {
            "symbol": symbol,  # store original (e.g. BRK.B)
            "data_type": "1d",
            "ts": ts,
            "open": quote.get("open"),
            "high": quote.get("high"),
            "low": quote.get("low"),
            "close": quote.get("close"),
            "volume": quote.get("volume"),
        }
    ).dropna(subset=["open", "high", "low", "close"])

    if df.empty:
        return None

    return df[["symbol", "data_type", "ts", "open", "high", "low", "close", "volume"]]


def run(symbols: list[str], mode: str, start_date_str: str | None, sessions: int, max_attempts: int, sleep_sec: float):
    engine = get_engine()
    log(f"[daily {mode}] start. symbols={'db-active' if not symbols else len(symbols)}")
    ensure_schema(engine)
    total = 0
    failed_symbols = 0
    skipped_symbols = 0

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

    if mode == "initial":
        if not start_date_str:
            start_date = date(2015, 1, 1)
        else:
            start_date = date.fromisoformat(start_date_str)
        end_date = resolve_daily_end_date()
        per_symbol_ranges = [(start_date, end_date)]
        latest_daily_ts_map: dict[str, int] = {}
        bootstrap_start_date = start_date
    else:
        # missing/maintenance:
        # use each symbol's latest stored daily ts as the resume point.
        end_date = resolve_daily_end_date()
        sched = last_trading_schedule(sessions)
        bootstrap_start_date = sched.index[0].date()
        latest_daily_ts_map = load_latest_daily_ts_map(engine)
        per_symbol_ranges = []

    if mode == "initial":
        log(
            f"[daily {mode}] window={per_symbol_ranges[0][0].isoformat()}..{per_symbol_ranges[0][1].isoformat()} "
            f"total_symbols={len(symbols)}"
        )
    else:
        log(
            f"[daily {mode}] end_date={end_date.isoformat()} total_symbols={len(symbols)} "
            f"resume_by_latest_db_ts=true bootstrap_if_missing={bootstrap_start_date.isoformat()}"
        )

    for idx, sym in enumerate(symbols, start=1):
        sym = sym.strip()
        if not sym:
            continue

        symbol_failed = False
        if mode == "initial":
            symbol_ranges = per_symbol_ranges
        else:
            latest_ts = latest_daily_ts_map.get(sym)
            if latest_ts is None:
                symbol_ranges = [(bootstrap_start_date, end_date)]
            else:
                latest_date = datetime.fromtimestamp(latest_ts, tz=timezone.utc).date()
                start_d = latest_date - timedelta(days=2)
                if start_d > end_date:
                    skipped_symbols += 1
                    if idx % PROGRESS_EVERY == 0 or idx == len(symbols):
                        log(
                            f"[daily {mode}] progress {idx}/{len(symbols)} "
                            f"skipped_symbols={skipped_symbols} failed_symbols={failed_symbols} inserted_rows={total}"
                        )
                    continue
                symbol_ranges = [(start_d, end_date)]

        for start_d, end_d in symbol_ranges:
            for attempt in range(max_attempts):
                try:
                    df = fetch_daily_yahoo_chart(sym, start_d, end_d)
                    with engine.begin() as conn:
                        total += insert_stock_ohlc(conn, df)
                    break
                except Exception as e:
                    log(f"[daily {mode}] {sym} {start_d}..{end_d} attempt={attempt+1}/{max_attempts} error={e}")
                    if attempt < max_attempts - 1:
                        sleep_backoff(attempt)
                    else:
                        symbol_failed = True
                        log(f"[daily {mode}] {sym} give up for now")

        if sleep_sec:
            time.sleep(sleep_sec)

        if symbol_failed:
            failed_symbols += 1

        if idx % PROGRESS_EVERY == 0 or idx == len(symbols):
            log(
                f"[daily {mode}] progress {idx}/{len(symbols)} "
                f"skipped_symbols={skipped_symbols} failed_symbols={failed_symbols} inserted_rows={total}"
            )

    engine.dispose()
    log(
        f"[daily {mode}] Total inserted: {total}, "
        f"skipped_symbols={skipped_symbols}, failed_symbols={failed_symbols}"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["initial", "missing"], required=True)
    ap.add_argument("--symbols", default=None)
    ap.add_argument("--symbols-file", default=None)
    ap.add_argument("--start-date", default=None, help="YYYY-MM-DD for initial mode (default 2015-01-01)")
    ap.add_argument(
        "--sessions",
        type=int,
        default=180,
        help="Bootstrap trading sessions only for symbols with no existing daily rows in missing mode",
    )
    ap.add_argument("--max-attempts", type=int, default=3)
    ap.add_argument("--sleep", type=float, default=0.0)
    args = ap.parse_args()

    syms = parse_symbols_arg(args.symbols, args.symbols_file)
    run(
        syms,
        mode=args.mode,
        start_date_str=args.start_date,
        sessions=args.sessions,
        max_attempts=args.max_attempts,
        sleep_sec=args.sleep,
    )


if __name__ == "__main__":
    main()
