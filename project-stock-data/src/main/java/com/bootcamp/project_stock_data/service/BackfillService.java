package com.bootcamp.project_stock_data.service;

public interface BackfillService {
  void updateSymbols();
  void backfillDailyMissing();
  void backfillIntradayMissing();
  void checkAndBackfill(); // orchestration: updateSymbols -> initial(new) -> missing(all)
}

