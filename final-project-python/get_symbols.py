import argparse
import datetime
import io
from typing import List

import pandas as pd
import requests
from sqlalchemy import MetaData, Table, select, update

from backfill_utils import ensure_schema, get_engine

SP500_URL = "https://stockanalysis.com/list/sp-500-stocks/"
SP500_WIKI = "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies"


def _normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    cols: list[str] = []
    for c in out.columns:
        if isinstance(c, tuple):
            c = " ".join(str(x) for x in c if x and str(x).lower() != "nan")
        cols.append(str(c).strip())
    out.columns = cols
    return out


def pick_table_by_columns(tables: list[pd.DataFrame], required_cols: set[str]) -> pd.DataFrame:
    required_cols = set(required_cols)
    for df in tables:
        dfn = _normalize_columns(df)
        if required_cols.issubset(set(dfn.columns)):
            return dfn
    available = [list(_normalize_columns(df).columns) for df in tables]
    raise ValueError(f"Cannot find table with columns {sorted(required_cols)}. Available columns: {available}")


def get_sp500_symbols_from_web() -> List[str]:
    headers = {"User-Agent": "Mozilla/5.0"}
    try:
        resp = requests.get(SP500_URL, headers=headers, timeout=15)
        resp.raise_for_status()
        tables = pd.read_html(io.StringIO(resp.text))
        df_sp500 = pick_table_by_columns(tables, {"Symbol"})
        return df_sp500["Symbol"].astype(str).tolist()
    except Exception as e:
        print(f"[WARN] StockAnalysis failed, fallback to Wikipedia. error={e}")

    tables = pd.read_html(SP500_WIKI)
    df_sp500 = tables[0]
    if "Symbol" not in df_sp500.columns:
        raise ValueError("Symbol column not found in Wikipedia table")
    return df_sp500["Symbol"].astype(str).tolist()


def get_symbols_table(engine):
    ensure_schema(engine)
    metadata = MetaData()
    return Table("stocks", metadata, autoload_with=engine)


def update_symbols(dry_run: bool = False):
    engine = get_engine()
    table = get_symbols_table(engine)
    today = datetime.date.today()

    raw_symbols = get_sp500_symbols_from_web()
    canonical_set = {s.strip() for s in raw_symbols if str(s).strip()}

    with engine.begin() as conn:
        existing = conn.execute(select(table.c.symbol, table.c.status)).fetchall()
        existing_map = {r[0]: bool(r[1]) for r in existing}

        added = 0
        reactivated = 0
        removed = 0

        for sym in canonical_set:
            if sym not in existing_map:
                added += 1
                if not dry_run:
                    conn.execute(table.insert().values(symbol=sym, status=True, date_added=today))
            else:
                if existing_map[sym] is False:
                    reactivated += 1
                    if not dry_run:
                        conn.execute(
                            update(table)
                            .where(table.c.symbol == sym)
                            .values(status=True, date_added=today, date_removed=None)
                        )

        for sym, was_active in existing_map.items():
            if was_active and sym not in canonical_set:
                removed += 1
                if not dry_run:
                    conn.execute(
                        update(table)
                        .where(table.c.symbol == sym)
                        .values(status=False, date_removed=today)
                    )

    print(f"update_symbols done. total={len(canonical_set)} added={added} reactivated={reactivated} removed={removed}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    update_symbols(dry_run=args.dry_run)


if __name__ == "__main__":
    main()
