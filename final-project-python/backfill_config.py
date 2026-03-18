API_MAX_DAYS = {
    # Max calendar days allowed per single provider fetch window.
    "1m": 7,
    "5m": 15,
    "15m": 30,
    "30m": 30,
    "1h": 60,
    "4h": 60,
    "1d": 99999,
}

MAIN_TABLE_RETENTION_DAYS = {
    # Main-table hot-data retention policy for scheduler/archive cleanup.
    "1m": 7,
    "5m": 30,
    "15m": 120,
    "30m": 120,
    "1h": 120,
    "4h": 120,
    "1d": 99999,
}

ARCHIVE_RETENTION_DAYS = {
    # Archive-table retention policy.
    "1m": 100,
    "5m": 100 * 2,
    "15m": 100 * 2,
    "30m": 100 * 2,
    "1h": 100 * 2,
    "4h": 100 * 2,
    "1d": 365,
}
