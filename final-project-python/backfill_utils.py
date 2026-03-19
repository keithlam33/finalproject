import os
import time
import random
from datetime import datetime, timedelta, timezone

import pandas as pd
from sqlalchemy import (
    BigInteger,
    Boolean,
    bindparam,
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
    return create_engine(
        get_db_url(),
        pool_pre_ping=True,
        pool_size=int(os.getenv("BACKFILL_POOL_SIZE", "3")),
        max_overflow=int(os.getenv("BACKFILL_MAX_OVERFLOW", "0")),
        pool_timeout=int(os.getenv("BACKFILL_POOL_TIMEOUT", "30")),
    )


def ensure_schema(engine) -> None:
    md = MetaData()
    Table(
        "stocks",
        md,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("symbol", String(255), nullable=False),
        Column("status", Boolean, nullable=False),
        Column("date_added", Date),
        Column("date_removed", Date),
        UniqueConstraint("symbol", name="uq_stocks_symbol"),
    )

    Table(
        "stock_ohlc",
        md,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("stock_id", BigInteger, ForeignKey("stocks.id"), nullable=False),
        Column("symbol", String(255), nullable=False),
        Column("data_type", String(255), nullable=False),
        Column("ts", BigInteger, nullable=False),
        Column("open", Float),
        Column("high", Float),
        Column("low", Float),
        Column("close", Float),
        Column("volume", BigInteger),
        Column("date_update", DateTime),
        UniqueConstraint("symbol", "data_type", "ts", name="uq_symbol_datatype_ts"),
    )

    Table(
        "stock_profile",
        md,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("stock_id", BigInteger, ForeignKey("stocks.id"), nullable=False),
        Column("symbol", String(255), nullable=False),
        Column("company_name", String(255)),
        Column("industry", String(255)),
        Column("market_cap", Float),
        Column("logo", String(255)),
        Column("date_update", Date),
        UniqueConstraint("symbol", name="uq_stock_profile_symbol"),
    )

    md.create_all(engine)
    with engine.begin() as conn:
        conn.execute(
            text(
                """
CREATE INDEX IF NOT EXISTS idx_stock_ohlc_data_type_symbol_ts
ON stock_ohlc (data_type, symbol, ts DESC)
"""
            )
        )


def yahoo_symbol(symbol: str) -> str:
    return symbol.replace(".", "-").strip()


def load_stock_id_map(conn, symbols: list[str]) -> dict[str, int]:
    cleaned = [str(s).strip() for s in symbols if str(s).strip()]
    if not cleaned:
        return {}

    stmt = text(
        """
SELECT id, symbol
FROM stocks
WHERE symbol IN :symbols
"""
    ).bindparams(bindparam("symbols", expanding=True))
    rows = conn.execute(stmt, {"symbols": cleaned}).fetchall()
    return {str(r[1]): int(r[0]) for r in rows}


def sleep_backoff(attempt: int, base_sec: float = 2.0, cap_sec: float = 60.0):
    delay = min(cap_sec, base_sec * (2 ** attempt))
    delay = delay * (0.9 + 0.2 * random.random())
    time.sleep(delay)


def to_epoch_sec(series: pd.Series) -> pd.Series:
    s = pd.Series(series)

    if pd.api.types.is_numeric_dtype(s):
        numeric = pd.to_numeric(s, errors="coerce")
        non_null = numeric.dropna()
        if non_null.empty:
            return numeric.astype("Int64")

        max_abs = float(non_null.abs().max())
        if max_abs >= 10**18:
            return (numeric // 10**9).astype("Int64")
        if max_abs >= 10**15:
            return (numeric // 10**6).astype("Int64")
        if max_abs >= 10**12:
            return (numeric // 10**3).astype("Int64")
        return numeric.astype("Int64")

    dt = pd.to_datetime(s, utc=True, errors="coerce")
    return dt.map(lambda x: int(x.timestamp()) if pd.notna(x) else pd.NA).astype("Int64")


def chunk_ranges(start: datetime, end: datetime, max_days: int):
    cur = start
    while cur < end:
        nxt = min(cur + timedelta(days=max_days), end)
        yield cur, nxt
        cur = nxt


def insert_stock_ohlc(conn, df: pd.DataFrame) -> int:
    if df is None or df.empty:
        return 0

    payload = df.copy()

    if "stock_id" not in payload.columns:
        stock_id_map = load_stock_id_map(
            conn,
            payload["symbol"].dropna().astype(str).unique().tolist(),
        )
        payload["stock_id"] = payload["symbol"].map(stock_id_map)
        missing_symbols = sorted(
            {
                str(s)
                for s in payload.loc[payload["stock_id"].isna(), "symbol"].dropna().astype(str).tolist()
            }
        )
        if missing_symbols:
            raise ValueError(f"Missing stock_id for symbols: {', '.join(missing_symbols[:10])}")
        payload["stock_id"] = payload["stock_id"].astype("int64")

    if "date_update" not in payload.columns:
        payload["date_update"] = datetime.utcnow()

    cols = list(payload.columns)
    insert_sql = f"""
    INSERT INTO stock_ohlc ({', '.join(cols)})
    VALUES ({', '.join([f":{c}" for c in cols])})
    ON CONFLICT ON CONSTRAINT uq_symbol_datatype_ts DO NOTHING
    """
    records = payload.to_dict(orient="records")
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
    eng = get_engine()
    ensure_schema(eng)
    eng.dispose()
    print("Schema ensured.")
