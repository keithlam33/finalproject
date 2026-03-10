import os
import time
import random
from datetime import datetime, timedelta, timezone

import pandas as pd
from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    Date,
    DateTime,
    Float,
    ForeignKey,
    MetaData,
    String,
    Table,
    UniqueConstraint,
    create_engine,
    text,
)


DEFAULT_DB_URL = "postgresql+psycopg2://postgres:admin1234@localhost:5532/bootcamp_2512"


def get_db_url() -> str:
    return os.getenv("BACKFILL_DB_URL") or os.getenv("DB_URL") or DEFAULT_DB_URL


def get_engine():
    # Keep pool small to avoid "too many clients" and rely on reuse.
    return create_engine(
        get_db_url(),
        pool_pre_ping=True,
        pool_size=int(os.getenv("BACKFILL_POOL_SIZE", "3")),
        max_overflow=int(os.getenv("BACKFILL_MAX_OVERFLOW", "0")),
        pool_timeout=int(os.getenv("BACKFILL_POOL_TIMEOUT", "30")),
    )

def ensure_schema(engine) -> None:
    """
    Create required tables if they don't exist.

    This keeps Python as the "schema owner" so Spring Boot can use ddl-auto: validate.
    Safe to call multiple times.
    """
    md = MetaData()

    # Matches StockEntity.java (@Table(name="stocks"))
    Table(
        "stocks",
        md,
        Column("symbol", String(255), primary_key=True),
        Column("status", Boolean, nullable=False),
        Column("date_added", Date),
        Column("date_removed", Date),
    )

    # Matches StockOhlcEntity.java (@Table(name="stock_ohlc"))
    # NOTE: Python insert code relies on ON CONFLICT ON CONSTRAINT uq_symbol_datatype_ts.
    Table(
        "stock_ohlc",
        md,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("symbol", String(255), ForeignKey("stocks.symbol"), nullable=False),
        Column("data_type", String(255), nullable=False),
        Column("ts", BigInteger, nullable=False),  # epoch seconds
        Column("open", Float),
        Column("high", Float),
        Column("low", Float),
        Column("close", Float),
        Column("volume", BigInteger),
        Column("date_update", DateTime),
        UniqueConstraint("symbol", "data_type", "ts", name="uq_symbol_datatype_ts"),
    )

    # Matches StockProfileEntity.java (@Table(name="stock_profile"))
    # Java uses LocalDate for date_update, so we use Date here.
    Table(
        "stock_profile",
        md,
        Column("symbol", String(255), ForeignKey("stocks.symbol"), primary_key=True),
        Column("company_name", String(255)),
        Column("industry", String(255)),
        Column("market_cap", Float),
        Column("logo", String(255)),
        Column("date_update", Date),
    )

    md.create_all(engine)


def yahoo_symbol(symbol: str) -> str:
    # Yahoo Finance class shares: BRK.B -> BRK-B, BF.B -> BF-B
    return symbol.replace(".", "-").strip()


def sleep_backoff(attempt: int, base_sec: float = 2.0, cap_sec: float = 60.0):
    delay = min(cap_sec, base_sec * (2 ** attempt))
    delay = delay * (0.9 + 0.2 * random.random())
    time.sleep(delay)


def to_epoch_sec(series: pd.Series) -> pd.Series:
    return pd.to_datetime(series, utc=True).astype("int64") // 10**9


def chunk_ranges(start: datetime, end: datetime, max_days: int):
    cur = start
    while cur < end:
        nxt = min(cur + timedelta(days=max_days), end)
        yield cur, nxt
        cur = nxt


def insert_stock_ohlc(conn, df: pd.DataFrame) -> int:
    if df is None or df.empty:
        return 0

    cols = list(df.columns)
    insert_sql = f"""
    INSERT INTO stock_ohlc ({', '.join(cols)})
    VALUES ({', '.join([f":{c}" for c in cols])})
    ON CONFLICT ON CONSTRAINT uq_symbol_datatype_ts DO NOTHING
    """
    records = df.to_dict(orient="records")
    conn.execute(text(insert_sql), records)
    return len(records)


def load_symbols_from_file(path: str) -> list[str]:
    out: list[str] = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s:
                out.append(s)
    return out


def parse_symbols_arg(symbols_arg: str | None, symbols_file: str | None) -> list[str]:
    if symbols_file:
        return load_symbols_from_file(symbols_file)
    if symbols_arg:
        return [s.strip() for s in symbols_arg.split(",") if s.strip()]
    return []


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


if __name__ == "__main__":
    # Allow a simple one-off init: `python backfill_utils.py`
    eng = get_engine()
    ensure_schema(eng)
    eng.dispose()
    print("Schema ensured.")
