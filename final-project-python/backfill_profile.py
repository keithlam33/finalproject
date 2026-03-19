import argparse
import time

import requests
from sqlalchemy import text

from backfill_utils import ensure_schema, get_engine, load_stock_id_map, parse_symbols_arg, sleep_backoff


UPSERT_PROFILE_SQL = text(
    """
INSERT INTO stock_profile(stock_id, symbol, company_name, industry, market_cap, logo, date_update)
VALUES (:stock_id, :symbol, :company_name, :industry, :market_cap, :logo, CURRENT_DATE)
ON CONFLICT (symbol) DO UPDATE SET
  stock_id = EXCLUDED.stock_id,
  company_name = EXCLUDED.company_name,
  industry = EXCLUDED.industry,
  market_cap = EXCLUDED.market_cap,
  logo = EXCLUDED.logo,
  date_update = CURRENT_DATE
"""
)


def normalize_base_url(url: str) -> str:
    return url[:-1] if url.endswith("/") else url


def load_active_symbols(conn, only_missing: bool) -> list[str]:
    if only_missing:
        rows = conn.execute(
            text(
                """
SELECT s.symbol
FROM stocks s
LEFT JOIN stock_profile p ON p.symbol = s.symbol
WHERE s.status = true
  AND p.symbol IS NULL
ORDER BY s.symbol
"""
            )
        ).fetchall()
    else:
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
    return [r[0] for r in rows]


def fetch_company_profile(url: str, symbol: str, timeout_sec: int):
    resp = requests.get(url, params={"symbol": symbol}, timeout=timeout_sec)
    resp.raise_for_status()
    if not resp.content:
        return {}
    return resp.json()


def wait_for_rate_limit(started_at: float, min_cycle_sec: float) -> None:
    if min_cycle_sec <= 0:
        return
    remaining = min_cycle_sec - (time.monotonic() - started_at)
    if remaining > 0:
        time.sleep(remaining)


def run(
    symbols: list[str],
    provider_base_url: str,
    company_path: str,
    max_attempts: int,
    sleep_sec: float,
    timeout_sec: int,
    only_missing: bool,
    dry_run: bool,
):
    engine = get_engine()
    ensure_schema(engine)

    with engine.begin() as conn:
        if symbols:
            target_symbols = symbols
        else:
            target_symbols = load_active_symbols(conn, only_missing=only_missing)
        stock_id_map = load_stock_id_map(conn, target_symbols)

    if not target_symbols:
        print("No symbols to process.")
        engine.dispose()
        return

    endpoint = f"{normalize_base_url(provider_base_url)}{company_path}"

    ok = 0
    failed: list[tuple[str, str]] = []

    total = len(target_symbols)
    print(f"profile sync start. total_symbols={total} endpoint={endpoint}")

    for idx, sym in enumerate(target_symbols, start=1):
        symbol = sym.strip()
        if not symbol:
            continue

        started_at = time.monotonic()
        payload = None
        last_error = None

        for attempt in range(max_attempts):
            try:
                payload = fetch_company_profile(endpoint, symbol, timeout_sec=timeout_sec)
                break
            except Exception as e:
                last_error = e
                print(
                    f"[profile] {symbol} attempt={attempt+1}/{max_attempts} error={e}"
                )
                if attempt < max_attempts - 1:
                    sleep_backoff(attempt)

        if payload is None:
            failed.append((symbol, str(last_error)))
            wait_for_rate_limit(started_at, sleep_sec)
            continue

        provider_symbol = (payload.get("symbol") or "").strip()
        if provider_symbol and provider_symbol != symbol:
            print(
                f"[profile] symbol mismatch request={symbol} provider={provider_symbol}. "
                f"Keep request symbol for DB FK consistency."
            )

        row = {
            "stock_id": stock_id_map.get(symbol),
            "symbol": symbol,
            "company_name": payload.get("companyName") or symbol,
            "industry": payload.get("industry") or "Unknown",
            "market_cap": payload.get("marketCap"),
            "logo": payload.get("logo"),
        }

        if row["stock_id"] is None:
            failed.append((symbol, "missing stock_id in stocks table"))
            wait_for_rate_limit(started_at, sleep_sec)
            continue

        if dry_run:
            print(f"[DRY-RUN] upsert {row['symbol']} ({row['company_name']})")
        else:
            try:
                with engine.begin() as conn:
                    conn.execute(UPSERT_PROFILE_SQL, row)
            except Exception as e:
                failed.append((symbol, f"db upsert error={e}"))
                wait_for_rate_limit(started_at, sleep_sec)
                continue

        ok += 1
        if idx % 20 == 0 or idx == total:
            print(f"progress {idx}/{total} synced={ok} failed={len(failed)}")

        wait_for_rate_limit(started_at, sleep_sec)

    print(f"profile sync done. synced={ok} failed={len(failed)}")
    if failed:
        print("sample failures:")
        for sym, err in failed[:20]:
            print(f" - {sym}: {err}")

    engine.dispose()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--symbols", default=None, help="Comma list, e.g. AAPL,MSFT")
    ap.add_argument("--symbols-file", default=None)
    ap.add_argument(
        "--provider-base-url",
        default="http://localhost:8100",
        help="Data provider base URL.",
    )
    ap.add_argument("--company-path", default="/company")
    ap.add_argument("--max-attempts", type=int, default=3)
    ap.add_argument(
        "--sleep",
        type=float,
        default=1.0,
        help="Minimum seconds per symbol. Default 1.0 fits Finnhub 30 requests / 30 seconds.",
    )
    ap.add_argument("--timeout", type=int, default=20, help="HTTP timeout seconds.")
    ap.add_argument(
        "--only-missing",
        action="store_true",
        help="Process only active symbols without stock_profile row.",
    )
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    syms = parse_symbols_arg(args.symbols, args.symbols_file)
    run(
        syms,
        provider_base_url=args.provider_base_url,
        company_path=args.company_path,
        max_attempts=args.max_attempts,
        sleep_sec=args.sleep,
        timeout_sec=args.timeout,
        only_missing=args.only_missing,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()
