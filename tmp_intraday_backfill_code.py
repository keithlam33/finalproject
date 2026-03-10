import yfinance as yf
import pandas as pd
import time
from datetime import datetime, timedelta
from sqlalchemy import text

%run _1_schema.ipynb
%run _6_config_intervals.ipynb

def to_epoch_sec(series):
    return (pd.to_datetime(series, utc=True).astype("int64") // 10**9)

def fetch_intraday(symbol: str, interval: str, start, end):
    df = yf.download(symbol, interval=interval, start=start, end=end, progress=False)
    if df is None or df.empty:
        return None

    df = df.reset_index()
    dt_col = df.columns[0]
    df["ts"] = to_epoch_sec(df[dt_col])
    df["symbol"] = symbol
    df["data_type"] = interval

    # handle multiindex columns like ('Close','AAPL')
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = [c[0] if isinstance(c, tuple) else c for c in df.columns]

    df = df.rename(columns={
        "Open": "open", "High": "high", "Low": "low", "Close": "close", "Volume": "volume"
    })

    df = df[["symbol", "data_type", "ts", "open", "high", "low", "close", "volume"]]
    df = df.dropna(subset=["open","high","low","close"])
    return df

def insert_intraday(df):
    if df is None or df.empty:
        return 0
    cols = list(df.columns)
    insert_sql = f"""
    INSERT INTO stock_ohlc ({', '.join(cols)})
    VALUES ({', '.join([f":{c}" for c in cols])})
    ON CONFLICT ON CONSTRAINT uq_symbol_datatype_ts DO NOTHING
    """
    records = df.to_dict(orient="records")
    engine = get_engine()
    with engine.begin() as conn:
        conn.execute(text(insert_sql), records)
    return len(records)

def _chunk_ranges(start, end, max_days):
    cur = start
    while cur < end:
        nxt = min(cur + timedelta(days=max_days), end)
        yield cur, nxt
        cur = nxt

def _interval_seconds(interval: str) -> int:
    if interval.endswith("m"):
        return int(interval[:-1]) * 60
    if interval.endswith("h"):
        return int(interval[:-1]) * 3600
    if interval.upper() == "D":
        return 86400
    raise ValueError(f"Unsupported interval: {interval}")

def _get_existing_ts(symbol: str, interval: str, start_ts: int, end_ts: int):
    sql = """
    SELECT ts
    FROM stock_ohlc
    WHERE symbol = :symbol
      AND data_type = :interval
      AND ts BETWEEN :start_ts AND :end_ts
    ORDER BY ts
    """
    engine = get_engine()
    with engine.begin() as conn:
        rows = conn.execute(
            text(sql),
            {"symbol": symbol, "interval": interval, "start_ts": start_ts, "end_ts": end_ts},
        ).fetchall()
    return [r[0] for r in rows]

def _find_missing_ranges(symbol: str, interval: str, start: datetime, end: datetime):
    step = _interval_seconds(interval)
    start_ts = int(start.replace(tzinfo=None).timestamp())
    end_ts = int(end.replace(tzinfo=None).timestamp())
    existing = set(_get_existing_ts(symbol, interval, start_ts, end_ts))
    if not existing:
        return [(start, end)]

    missing_ranges = []
    cur = start_ts
    gap_start = None

    while cur <= end_ts:
        if cur not in existing:
            if gap_start is None:
                gap_start = cur
        else:
            if gap_start is not None:
                missing_ranges.append((gap_start, cur - step))
                gap_start = None
        cur += step

    if gap_start is not None:
        missing_ranges.append((gap_start, end_ts))

    def ts_to_dt(ts):
        return datetime.utcfromtimestamp(ts)

    return [(ts_to_dt(a), ts_to_dt(b)) for a, b in missing_ranges]

def backfill_intraday(
    symbols,
    interval="1m",
    days=None,
    sleep_sec=1,
    repair_missing=True,
):
    if days is None:
        days = MAIN_TABLE_RETENTION_DAYS.get(interval, 7)

    api_max = API_MAX_DAYS.get(interval, 7)
    end = datetime.utcnow()
    start = end - timedelta(days=days)

    total = 0
    for s in symbols:
        print(f"[{s}] backfill {interval} from {start.isoformat()} to {end.isoformat()}")

        # full window, chunked by API limits
        for a, b in _chunk_ranges(start, end, api_max):
            df = fetch_intraday(s, interval, a, b)
            total += insert_intraday(df)
            time.sleep(sleep_sec)

        # repair missing gaps inside window
        if repair_missing:
            gaps = _find_missing_ranges(s, interval, start, end)
            for ga, gb in gaps:
                for a, b in _chunk_ranges(ga, gb, api_max):
                    df = fetch_intraday(s, interval, a, b)
                    total += insert_intraday(df)
                    time.sleep(sleep_sec)

        print(f"[{s}] done")

    print("Total inserted:", total)

# example:
# backfill_intraday(["AAPL","MSFT"], interval="1m")  # uses MAIN_TABLE_RETENTION_DAYS
# backfill_intraday(["AAPL"], interval="5m", days=14)
