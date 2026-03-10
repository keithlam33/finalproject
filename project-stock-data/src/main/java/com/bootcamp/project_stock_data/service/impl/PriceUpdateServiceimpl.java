package com.bootcamp.project_stock_data.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.service.PriceUpdateService;

@Service
public class PriceUpdateServiceimpl implements PriceUpdateService {

  private static class CandleBucket { // in-progress 1m candle
    long minuteStart;
    Double open;
    Double high;
    Double low;
    Double close;
    Long volume;
  }

  private final Map<String, CandleBucket> current = new ConcurrentHashMap<>();
  private final Map<String, Long> lastCumulativeVolume = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<FinalCandle> finalized = new ConcurrentLinkedQueue<>();

  private volatile String lastMarketState;
  private volatile Long lastMarketTime; // REGULAR-only progress time

  @Override
  public void updateQuotes(YahooQuoteDTO resp) {
    if (resp == null || resp.getQuotes() == null)
      return;

    for (YahooQuoteDTO.QuoteDto q : resp.getQuotes()) {
      if (q == null || q.getSymbol() == null || q.getPrice() == null || q.getMarketTime() == null)
        continue;

      String symbol = q.getSymbol().trim();
      if (symbol.isEmpty())
        continue;

      // 1) Always update lastMarketState (REGULAR/PRE/POST/CLOSED) for scheduler streak logic.
      this.lastMarketState = q.getMarketState();

      // 2) Update lastMarketTime ONLY when REGULAR (stable "data progress" time for aggregation trigger).
      if (q.getMarketState() == null || "REGULAR".equalsIgnoreCase(q.getMarketState())) {
        this.lastMarketTime = q.getMarketTime();
      }

      // 3) Only build candles during REGULAR session
      if (q.getMarketState() != null && !"REGULAR".equalsIgnoreCase(q.getMarketState()))
        continue;

      long marketTime = q.getMarketTime(); // epochSec
      long minuteStart = marketTime - (marketTime % 60);

      Long deltaVol = computeDeltaVolume(symbol, q.getTradeVolume());

      CandleBucket bucket = current.get(symbol);
      if (bucket == null) {
        CandleBucket nb = new CandleBucket();
        nb.minuteStart = minuteStart;
        nb.open = q.getPrice();
        nb.high = q.getPrice();
        nb.low = q.getPrice();
        nb.close = q.getPrice();
        nb.volume = deltaVol;
        current.put(symbol, nb);
        continue;
      }

      // rollover to new minute
      if (bucket.minuteStart != minuteStart) {
        enqueue(symbol, bucket);

        CandleBucket nb = new CandleBucket();
        nb.minuteStart = minuteStart;
        nb.open = q.getPrice();
        nb.high = q.getPrice();
        nb.low = q.getPrice();
        nb.close = q.getPrice();
        nb.volume = deltaVol;
        current.put(symbol, nb);
        continue;
      }

      // update same minute
      bucket.close = q.getPrice();
      bucket.high = bucket.high == null ? q.getPrice() : Math.max(bucket.high, q.getPrice());
      bucket.low = bucket.low == null ? q.getPrice() : Math.min(bucket.low, q.getPrice());
      bucket.volume = safeAdd(bucket.volume, deltaVol);
    }
  }

  private Long computeDeltaVolume(String symbol, Long currCum) {
    if (currCum == null)
      return null;

    Long prev = lastCumulativeVolume.put(symbol, currCum);
    if (prev == null)
      return 0L;

    long delta = currCum - prev;
    if (delta < 0) {
      // reset / bad data: treat as 0
      return 0L;
    }
    return delta;
  }

  private Long safeAdd(Long a, Long b) {
    if (a == null)
      return b;
    if (b == null)
      return a;
    return a + b;
  }

  @Override
  public void forceFinalizeBefore(long cutoffMinuteStartEpochSec) {
    for (Map.Entry<String, CandleBucket> e : current.entrySet()) {
      CandleBucket b = e.getValue();
      if (b != null && b.minuteStart < cutoffMinuteStartEpochSec) {
        enqueue(e.getKey(), b);
      }
    }
  }

  @Override
  public List<FinalCandle> drainFinalized() {
    List<FinalCandle> out = new ArrayList<>();
    while (true) {
      FinalCandle c = finalized.poll();
      if (c == null)
        break;
      out.add(c);
    }
    return out;
  }

  private void enqueue(String symbol, CandleBucket b) {
    if (b.open == null || b.high == null || b.low == null || b.close == null)
      return;

    FinalCandle c = new FinalCandle();
    c.symbol = symbol;
    c.dataType = "1m";
    c.ts = b.minuteStart;
    c.open = b.open;
    c.high = b.high;
    c.low = b.low;
    c.close = b.close;
    c.volume = b.volume;
    finalized.add(c);
  }

  @Override
  public String getLastMarketState() {
    return this.lastMarketState;
  }

  @Override
  public Long getLastMarketTime() {
    return this.lastMarketTime;
  }
}
