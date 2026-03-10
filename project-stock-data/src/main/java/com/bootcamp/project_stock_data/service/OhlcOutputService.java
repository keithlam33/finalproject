package com.bootcamp.project_stock_data.service;

public interface OhlcOutputService {
  boolean isMarketOpenNow();

  void finalizeOneMinute();

  // Aggregate from stored "1m" candles, then persist into targetDataType (e.g. "5m", "15m", "1h", "4h").
  void aggregateFrom1mAndPersist(String targetDataType, int minutes);

  // Aggregate from stored "1m" candles, then persist into "1d".
  void finalizeDaily();
}

