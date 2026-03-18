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


def _table_exists(conn, table_name: str) -> bool:
    return bool(
        conn.execute(
            text("SELECT to_regclass(:table_name) IS NOT NULL"),
            {"table_name": table_name},
        ).scalar()
    )


def _column_exists(conn, table_name: str, column_name: str) -> bool:
    return bool(
        conn.execute(
            text(
                """
SELECT 1
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = :table_name
  AND column_name = :column_name
"""
            ),
            {"table_name": table_name, "column_name": column_name},
        ).scalar()
    )


def _constraint_exists(conn, table_name: str, constraint_name: str) -> bool:
    return bool(
        conn.execute(
            text(
                """
SELECT 1
FROM information_schema.table_constraints
WHERE table_schema = current_schema()
  AND table_name = :table_name
  AND constraint_name = :constraint_name
"""
            ),
            {"table_name": table_name, "constraint_name": constraint_name},
        ).scalar()
    )


def _primary_key_columns(conn, table_name: str) -> list[str]:
    rows = conn.execute(
        text(
            """
SELECT a.attname
FROM pg_index i
JOIN pg_attribute a
  ON a.attrelid = i.indrelid
 AND a.attnum = ANY(i.indkey)
WHERE i.indrelid = to_regclass(:table_name)
  AND i.indisprimary
ORDER BY array_position(i.indkey, a.attnum)
"""
        ),
        {"table_name": table_name},
    ).fetchall()
    return [r[0] for r in rows]


def _drop_primary_key(conn, table_name: str) -> None:
    pk_name = conn.execute(
        text(
            """
SELECT conname
FROM pg_constraint
WHERE conrelid = to_regclass(:table_name)
  AND contype = 'p'
"""
        ),
        {"table_name": table_name},
    ).scalar()
    if pk_name:
        conn.execute(text(f'ALTER TABLE {table_name} DROP CONSTRAINT "{pk_name}"'))


def _ensure_unique_constraint(conn, table_name: str, constraint_name: str, columns: str) -> None:
    if _constraint_exists(conn, table_name, constraint_name):
        return
    conn.execute(
        text(f"ALTER TABLE {table_name} ADD CONSTRAINT {constraint_name} UNIQUE ({columns})")
    )


def _foreign_key_constraints(conn, table_name: str, column_name: str, ref_table_name: str) -> list[str]:
    rows = conn.execute(
        text(
            """
SELECT tc.constraint_name
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_name = kcu.constraint_name
 AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage ccu
  ON tc.constraint_name = ccu.constraint_name
 AND tc.table_schema = ccu.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema = current_schema()
  AND tc.table_name = :table_name
  AND kcu.column_name = :column_name
  AND ccu.table_name = :ref_table_name
"""
        ),
        {
            "table_name": table_name,
            "column_name": column_name,
            "ref_table_name": ref_table_name,
        },
    ).fetchall()
    return [r[0] for r in rows]


def _drop_symbol_foreign_keys_to_stocks(conn) -> None:
    for table_name in ("stock_profile", "stock_ohlc"):
        if not _table_exists(conn, table_name):
            continue
        for constraint_name in _foreign_key_constraints(conn, table_name, "symbol", "stocks"):
            conn.execute(text(f'ALTER TABLE {table_name} DROP CONSTRAINT "{constraint_name}"'))


def _ensure_symbol_foreign_key(conn, table_name: str, constraint_name: str) -> None:
    if (
        not _table_exists(conn, table_name)
        or _constraint_exists(conn, table_name, constraint_name)
        or _foreign_key_constraints(conn, table_name, "symbol", "stocks")
    ):
        return
    conn.execute(
        text(
            f"""
ALTER TABLE {table_name}
ADD CONSTRAINT {constraint_name}
FOREIGN KEY (symbol) REFERENCES stocks(symbol)
"""
        )
    )


def _ensure_bigint_id_column(conn, table_name: str, sequence_name: str) -> None:
    if not _column_exists(conn, table_name, "id"):
        conn.execute(text(f"ALTER TABLE {table_name} ADD COLUMN id BIGINT"))
    conn.execute(text(f"CREATE SEQUENCE IF NOT EXISTS {sequence_name}"))
    conn.execute(text(f"ALTER SEQUENCE {sequence_name} OWNED BY {table_name}.id"))
    conn.execute(
        text(f"ALTER TABLE {table_name} ALTER COLUMN id SET DEFAULT nextval('{sequence_name}')")
    )
    conn.execute(text(f"UPDATE {table_name} SET id = nextval('{sequence_name}') WHERE id IS NULL"))
    conn.execute(
        text(
            f"SELECT setval('{sequence_name}', COALESCE((SELECT MAX(id) FROM {table_name}), 0) + 1, false)"
        )
    )
    conn.execute(text(f"ALTER TABLE {table_name} ALTER COLUMN id SET NOT NULL"))


def _migrate_stocks_table(conn) -> None:
    if not _table_exists(conn, "stocks"):
        return

    _ensure_bigint_id_column(conn, "stocks", "stocks_id_seq")
    _ensure_unique_constraint(conn, "stocks", "uq_stocks_symbol", "symbol")

    if _primary_key_columns(conn, "stocks") != ["id"]:
        _drop_symbol_foreign_keys_to_stocks(conn)
        _drop_primary_key(conn, "stocks")
        conn.execute(text("ALTER TABLE stocks ADD PRIMARY KEY (id)"))


def _migrate_stock_profile_table(conn) -> None:
    if not _table_exists(conn, "stock_profile"):
        return

    _ensure_bigint_id_column(conn, "stock_profile", "stock_profile_id_seq")
    _ensure_unique_constraint(conn, "stock_profile", "uq_stock_profile_symbol", "symbol")

    if _primary_key_columns(conn, "stock_profile") != ["id"]:
        _drop_primary_key(conn, "stock_profile")
        conn.execute(text("ALTER TABLE stock_profile ADD PRIMARY KEY (id)"))


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
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("symbol", String(255), nullable=False),
        Column("status", Boolean, nullable=False),
        Column("date_added", Date),
        Column("date_removed", Date),
        UniqueConstraint("symbol", name="uq_stocks_symbol"),
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
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("symbol", String(255), ForeignKey("stocks.symbol"), nullable=False),
        Column("company_name", String(255)),
        Column("industry", String(255)),
        Column("market_cap", Float),
        Column("logo", String(255)),
        Column("date_update", Date),
        UniqueConstraint("symbol", name="uq_stock_profile_symbol"),
    )

    md.create_all(engine)
    with engine.begin() as conn:
        _migrate_stocks_table(conn)
        _migrate_stock_profile_table(conn)
        _ensure_symbol_foreign_key(conn, "stock_profile", "fk_stock_profile_symbol")
        _ensure_symbol_foreign_key(conn, "stock_ohlc", "fk_stock_ohlc_symbol")
        # Speed up MAX(ts) per interval/symbol lookups used by missing/tail backfill logic.
        conn.execute(
            text(
                """
CREATE INDEX IF NOT EXISTS idx_stock_ohlc_data_type_symbol_ts
ON stock_ohlc (data_type, symbol, ts DESC)
"""
            )
        )


def yahoo_symbol(symbol: str) -> str:
    # Yahoo Finance class shares: BRK.B -> BRK-B, BF.B -> BF-B
    return symbol.replace(".", "-").strip()


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

        # yfinance / provider payloads may already be epoch numbers.
        # Detect the unit by magnitude to avoid re-parsing epoch seconds as ns,
        # which would collapse all rows to ts=1.
        if max_abs >= 10**18:
            return (numeric // 10**9).astype("Int64")  # ns
        if max_abs >= 10**15:
            return (numeric // 10**6).astype("Int64")  # us
        if max_abs >= 10**12:
            return (numeric // 10**3).astype("Int64")  # ms
        return numeric.astype("Int64")  # already seconds

    dt = pd.to_datetime(s, utc=True, errors="coerce")
    # Do not divide raw datetime int64 blindly here.
    # On some pandas builds, datetime64[s] astype("int64") is already epoch seconds,
    # so dividing by 1e9 would collapse modern timestamps to 1.
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

    cols = list(df.columns)
    insert_sql = f"""
    INSERT INTO stock_ohlc ({', '.join(cols)})
    VALUES ({', '.join([f":{c}" for c in cols])})
    ON CONFLICT ON CONSTRAINT uq_symbol_datatype_ts DO NOTHING
    """
    records = df.to_dict(orient="records")
    conn.execute(text(insert_sql), records)
    # For this executemany path, DB-driver rowcount can under-report badly.
    # Return batch size so progress reflects actual fetched/written workload.
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
