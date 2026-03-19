package com.bootcamp.project_stock_data.service;

import java.util.List;

import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;

public interface PriceUpdateService {

  // A completed (settled) 1-minute candle ready to persist.
  public static class FinalCandle {
    public String symbol;
    public String dataType; // "1m"
    public long ts; // minuteStart epochSec
    public Double open;
    public Double high;
    public Double low;
    public Double close;
    public Long volume;
  }

  // Best-effort snapshot of the currently building 1m candle.
  public static class LiveCandle {
    public String symbol;
    public long ts; // minuteStart epochSec
    public Double open;
    public Double high;
    public Double low;
    public Double close;
    public Long volume;
  }

  // Scheduler pulls provider once, then passes the response here to build 1m buckets, etc.
  void updateQuotes(YahooQuoteDTO resp);

  void forceFinalizeBefore(long cutoffMinuteStartEpochSec);

  List<FinalCandle> drainFinalized();

  // Best-effort latest market metadata observed during updateQuotes().
  // Used by scheduler to confirm open/close without calling extra APIs.
  String getLastMarketState();

  Long getLastMarketTime(); // epochSec

  LiveCandle getCurrentCandle(String symbol);
}
