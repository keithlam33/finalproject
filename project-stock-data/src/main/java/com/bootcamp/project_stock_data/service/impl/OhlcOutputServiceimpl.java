package com.bootcamp.project_stock_data.service.impl;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.entity.StockEntity;
import com.bootcamp.project_stock_data.entity.StockOhlcEntity;
import com.bootcamp.project_stock_data.repository.StockOhlcRepository;
import com.bootcamp.project_stock_data.repository.StockRepository;
import com.bootcamp.project_stock_data.service.OhlcOutputService;
import com.bootcamp.project_stock_data.service.PriceUpdateService;
import com.bootcamp.project_stock_data.service.PriceUpdateService.FinalCandle;

@Service
public class OhlcOutputServiceimpl implements OhlcOutputService {

  private static final ZoneId NY = ZoneId.of("America/New_York");
  private static final LocalTime OPEN = LocalTime.of(9, 30);
  private static final LocalTime CLOSE = LocalTime.of(16, 0);

  @Autowired
  private PriceUpdateService priceUpdateService;

  @Autowired
  private StockOhlcRepository stockOhlcRepository;

  @Autowired
  private StockRepository stockRepository;

  @Override
  public boolean isMarketOpenNow() {
    ZonedDateTime now = ZonedDateTime.now(NY);
    DayOfWeek dow = now.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
      return false;
    LocalTime t = now.toLocalTime();
    return !t.isBefore(OPEN) && t.isBefore(CLOSE);
  }

  @Override
  public void finalizeOneMinute() {
    long nowEpoch = System.currentTimeMillis() / 1000;
    long nowMinuteStart = nowEpoch - (nowEpoch % 60);

    // force finalize any candle older than current minute
    priceUpdateService.forceFinalizeBefore(nowMinuteStart);

    List<FinalCandle> candles = priceUpdateService.drainFinalized();
    Map<String, Long> stockIdBySymbol = loadActiveStockIdMap();
    for (FinalCandle c : candles) {
      Long stockId = stockIdBySymbol.get(c.symbol);
      if (stockId == null) {
        continue;
      }
      StockOhlcEntity e = new StockOhlcEntity();
      e.setStockId(stockId);
      e.setSymbol(c.symbol);
      e.setDataType(c.dataType); // "1m"
      e.setTs(c.ts);             // minuteStart epochSec
      e.setOpen(c.open);
      e.setHigh(c.high);
      e.setLow(c.low);
      e.setClose(c.close);
      e.setVolume(c.volume);
      e.setDateUpdate(java.time.LocalDateTime.now());
      try {
        stockOhlcRepository.save(e);
      } catch (DataIntegrityViolationException ignoreDup) {
        // unique(symbol,data_type,ts) already exists
      }
    }
  }

  @Override
  public void aggregateFrom1mAndPersist(String targetDataType, int minutes) {
    if (minutes <= 0)
      return;

    long windowSec = minutes * 60L;

    ZonedDateTime nowNy = ZonedDateTime.now(NY);
    LocalDate nyDate = nowNy.toLocalDate();
    long sessionStart = nyDate.atTime(OPEN).atZone(NY).toEpochSecond();
    long sessionEndHard = nyDate.atTime(CLOSE).atZone(NY).toEpochSecond();

    // We do NOT use server "now" cutoff here.
    // Scheduler is responsible for calling this when a boundary is reached (based on marketTime).
    // Here we only:
    // 1) find where we left off for this symbol+targetDataType
    // 2) aggregate the next windows if the required 1m candles exist in DB

    List<StockEntity> active = stockRepository.findByStatusTrue();
    for (StockEntity s : active) {
      if (s == null || s.getSymbol() == null)
        continue;

      String symbol = s.getSymbol().trim();
      if (symbol.isEmpty())
        continue;

      StockOhlcEntity lastAgg = stockOhlcRepository.findTopBySymbolAndDataTypeOrderByTsDesc(symbol, targetDataType);

      long startWindowStart;
      if (lastAgg == null || lastAgg.getTs() == null || lastAgg.getTs() < sessionStart) {
        startWindowStart = sessionStart;
      } else {
        startWindowStart = lastAgg.getTs() + windowSec;
      }

      // Safety: don't try to catch up an entire day in one tick
      int maxWindowsPerRun = 50;
      int produced = 0;

      for (long ws = startWindowStart; ws + windowSec <= sessionEndHard; ws += windowSec) {
        if (produced >= maxWindowsPerRun)
          break;

        long weInclusive = ws + windowSec - 1;

        List<StockOhlcEntity> base = stockOhlcRepository
            .findBySymbolAndDataTypeAndTsBetweenOrderByTsAsc(symbol, "1m", ws, weInclusive);

        // If 1m candles not ready yet, stop advancing for this symbol (next tick will retry).
        if (base == null || base.isEmpty())
          break;

        // Optional strictness: require "full" minute count (e.g. 5 bars for 5m)
        // If you want max-real-time, keep it loose and just aggregate what exists.
        // If you want correctness, require base.size() == minutes.
        if (base.size() < minutes)
          break;

        StockOhlcEntity agg = aggregateWindow(s.getId(), symbol, targetDataType, ws, base);
        if (agg == null)
          break;

        try {
          stockOhlcRepository.save(agg);
          produced++;
        } catch (DataIntegrityViolationException ignoreDup) {
          // unique(symbol,data_type,ts) already exists
        }
      }
    }
  }

  @Override
  public void finalizeDaily() {
    ZonedDateTime nowNy = ZonedDateTime.now(NY);
    LocalDate nyDate = nowNy.toLocalDate();

    long sessionStart = nyDate.atTime(OPEN).atZone(NY).toEpochSecond();
    long sessionEndHard = nyDate.atTime(CLOSE).atZone(NY).toEpochSecond();

    List<StockEntity> active = stockRepository.findByStatusTrue();
    for (StockEntity s : active) {
      if (s == null || s.getSymbol() == null)
        continue;

      String symbol = s.getSymbol().trim();
      if (symbol.isEmpty())
        continue;

      StockOhlcEntity existing = stockOhlcRepository.findBySymbolAndDataTypeAndTs(symbol, "1d", sessionStart);
      if (existing != null)
        continue;

      // Use actual last 1m inside regular window as session end (handles early close)
      StockOhlcEntity last1m = stockOhlcRepository.findTopBySymbolAndDataTypeAndTsBetweenOrderByTsDesc(
          symbol, "1m", sessionStart, sessionEndHard - 1);

      if (last1m == null)
        continue;

      long sessionEnd = Math.min(sessionEndHard, last1m.getTs() + 60);

      List<StockOhlcEntity> base = stockOhlcRepository.findBySymbolAndDataTypeAndTsBetweenOrderByTsAsc(
          symbol, "1m", sessionStart, sessionEnd - 1);

      if (base == null || base.isEmpty())
        continue;

      StockOhlcEntity daily = aggregateWindow(s.getId(), symbol, "1d", sessionStart, base);
      if (daily == null)
        continue;

      try {
        stockOhlcRepository.save(daily);
      } catch (DataIntegrityViolationException ignoreDup) {
        // unique(symbol,data_type,ts) already exists
      }
    }
  }

  private StockOhlcEntity aggregateWindow(
      Long stockId, String symbol, String dataType, long windowStartEpochSec, List<StockOhlcEntity> baseAsc) {

    StockOhlcEntity first = baseAsc.get(0);
    StockOhlcEntity last = baseAsc.get(baseAsc.size() - 1);

    Double open = first.getOpen();
    Double close = last.getClose();
    if (open == null || close == null)
      return null;

    Double high = null;
    Double low = null;

    long volSum = 0L;
    boolean hasVol = false;

    for (StockOhlcEntity c : baseAsc) {
      if (c == null)
        continue;

      if (c.getHigh() != null)
        high = (high == null) ? c.getHigh() : Math.max(high, c.getHigh());
      if (c.getLow() != null)
        low = (low == null) ? c.getLow() : Math.min(low, c.getLow());

      if (c.getVolume() != null) {
        volSum += c.getVolume();
        hasVol = true;
      }
    }

    StockOhlcEntity e = new StockOhlcEntity();
    e.setStockId(stockId);
    e.setSymbol(symbol);
    e.setDataType(dataType);
    e.setTs(windowStartEpochSec);
    e.setOpen(open);
    e.setHigh(high);
    e.setLow(low);
    e.setClose(close);
    e.setVolume(hasVol ? volSum : null);
    e.setDateUpdate(java.time.LocalDateTime.now());
    return e;
  }

  private Map<String, Long> loadActiveStockIdMap() {
    List<StockEntity> active = stockRepository.findByStatusTrue();
    Map<String, Long> out = new HashMap<>();
    for (StockEntity stock : active) {
      if (stock == null || stock.getId() == null || stock.getSymbol() == null) {
        continue;
      }
      out.put(stock.getSymbol().trim(), stock.getId());
    }
    return out;
  }
}
