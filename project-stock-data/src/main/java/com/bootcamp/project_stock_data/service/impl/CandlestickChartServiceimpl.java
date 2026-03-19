package com.bootcamp.project_stock_data.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.dto.CachedQuoteDto;
import com.bootcamp.project_stock_data.dto.CandlestickChartDto;
import com.bootcamp.project_stock_data.entity.StockOhlcEntity;
import com.bootcamp.project_stock_data.entity.StockProfileEntity;
import com.bootcamp.project_stock_data.mapper.DtoMapper;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.repository.StockOhlcRepository;
import com.bootcamp.project_stock_data.repository.StockProfileRepository;
import com.bootcamp.project_stock_data.service.CandlestickChartService;
import com.bootcamp.project_stock_data.service.PriceUpdateService;
import com.bootcamp.project_stock_data.service.PriceUpdateService.LiveCandle;
import com.bootcamp.project_stock_data.service.QuoteCacheService;

@Service
public class CandlestickChartServiceimpl implements CandlestickChartService {

  private static final ZoneId NY = ZoneId.of("America/New_York");
  private static final LocalTime SESSION_OPEN = LocalTime.of(9, 30);

  @Autowired
  private QuoteCacheService quoteCacheService;

  @Autowired
  private StockProfileRepository stockProfileRepository;

  @Autowired
  private StockOhlcRepository stockOhlcRepository;

  @Autowired
  private PriceUpdateService priceUpdateService;

  @Autowired
  private DtoMapper dtoMapper;

  @Override
  public CandlestickChartDto getCandlestick(String symbol, String interval, Integer limit, Long beforeTs) {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("Symbol is required.");
    }

    String s = symbol.trim();
    String i = interval == null ? null : interval.trim();
    if (i == null || i.isEmpty()) {
      throw new IllegalArgumentException("Interval is required.");
    }

    StockProfileEntity profile = stockProfileRepository.findBySymbol(s).orElse(null);
    List<StockOhlcEntity> candles = findCandles(s, i, limit, beforeTs);

    CachedQuoteDto cached = quoteCacheService.getLatestQuote(s);
    YahooQuoteDTO.QuoteDto quote = (cached != null) ? toYahooQuote(cached) : fallbackQuoteFromDbLatest(s, i);
    List<StockOhlcEntity> mergedCandles = mergeLiveCandles(s, i, limit, beforeTs, candles, quote);

    return dtoMapper.map(profile, quote, mergedCandles, i);
  }

  private List<StockOhlcEntity> findCandles(String symbol, String interval, Integer limit, Long beforeTs) {
    if (limit == null || limit <= 0) {
      List<StockOhlcEntity> candles = stockOhlcRepository.findBySymbolAndDataTypeOrderByTsAsc(symbol, interval);
      return normalizeCandles(interval, candles);
    }

    Pageable pageable = PageRequest.of(0, limit);
    List<StockOhlcEntity> desc = (beforeTs == null)
        ? stockOhlcRepository.findBySymbolAndDataTypeOrderByTsDesc(symbol, interval, pageable)
        : stockOhlcRepository.findBySymbolAndDataTypeAndTsLessThanOrderByTsDesc(symbol, interval, beforeTs, pageable);

    List<StockOhlcEntity> asc = new ArrayList<>(desc);
    Collections.reverse(asc);
    return normalizeCandles(interval, asc);
  }

  private List<StockOhlcEntity> normalizeCandles(String interval, List<StockOhlcEntity> candles) {
    if (!"1d".equals(interval) || candles == null || candles.size() <= 1) {
      return candles;
    }
    return dedupeDailyCandles(candles);
  }

  private List<StockOhlcEntity> dedupeDailyCandles(List<StockOhlcEntity> candles) {
    Map<LocalDate, StockOhlcEntity> byUtcDate = new LinkedHashMap<>();

    for (StockOhlcEntity candle : candles) {
      if (candle == null || candle.getTs() == null) {
        continue;
      }

      LocalDate utcDate = Instant.ofEpochSecond(candle.getTs())
          .atZone(ZoneOffset.UTC)
          .toLocalDate();

      StockOhlcEntity existing = byUtcDate.get(utcDate);
      if (existing == null || existing.getTs() == null || candle.getTs() >= existing.getTs()) {
        byUtcDate.put(utcDate, candle);
      }
    }

    return new ArrayList<>(byUtcDate.values());
  }

  private List<StockOhlcEntity> mergeLiveCandles(
      String symbol,
      String interval,
      Integer limit,
      Long beforeTs,
      List<StockOhlcEntity> candles,
      YahooQuoteDTO.QuoteDto quote) {

    if (beforeTs != null) {
      return candles;
    }

    StockOhlcEntity live = buildLiveCandle(symbol, interval, quote);
    if (live == null) {
      return candles;
    }

    Map<Long, StockOhlcEntity> byTs = new LinkedHashMap<>();
    if (candles != null) {
      for (StockOhlcEntity candle : candles) {
        if (candle == null || candle.getTs() == null) {
          continue;
        }
        byTs.put(candle.getTs(), candle);
      }
    }
    byTs.put(live.getTs(), live);

    List<StockOhlcEntity> merged = new ArrayList<>(byTs.values());
    merged.sort((left, right) -> Long.compare(left.getTs(), right.getTs()));

    if (limit != null && limit > 0 && merged.size() > limit) {
      return new ArrayList<>(merged.subList(merged.size() - limit, merged.size()));
    }
    return merged;
  }

  private StockOhlcEntity buildLiveCandle(String symbol, String interval, YahooQuoteDTO.QuoteDto quote) {
    if (quote == null || quote.getMarketState() == null || !"REGULAR".equalsIgnoreCase(quote.getMarketState())) {
      return null;
    }

    if ("1d".equals(interval)) {
      return null;
    }

    LiveCandle live1m = priceUpdateService.getCurrentCandle(symbol);
    if (live1m == null) {
      return null;
    }

    if ("1m".equals(interval)) {
      return toEntity(symbol, interval, live1m.ts, live1m.open, live1m.high, live1m.low, live1m.close, live1m.volume);
    }

    long intervalSec = intervalToSeconds(interval);
    if (intervalSec <= 60L) {
      return null;
    }

    long windowStart = alignToSessionWindow(live1m.ts, intervalSec);
    long completedUpperBound = live1m.ts - 1;

    List<StockOhlcEntity> base = new ArrayList<>();
    if (completedUpperBound >= windowStart) {
      List<StockOhlcEntity> completed = stockOhlcRepository
          .findBySymbolAndDataTypeAndTsBetweenOrderByTsAsc(symbol, "1m", windowStart, completedUpperBound);
      if (completed != null) {
        base.addAll(completed);
      }
    }
    base.add(toEntity(symbol, "1m", live1m.ts, live1m.open, live1m.high, live1m.low, live1m.close, live1m.volume));

    return aggregateWindow(symbol, interval, windowStart, base);
  }

  private StockOhlcEntity aggregateWindow(
      String symbol,
      String interval,
      long windowStartEpochSec,
      List<StockOhlcEntity> baseAsc) {

    if (baseAsc == null || baseAsc.isEmpty()) {
      return null;
    }

    StockOhlcEntity first = baseAsc.get(0);
    StockOhlcEntity last = baseAsc.get(baseAsc.size() - 1);
    if (first == null || last == null || first.getOpen() == null || last.getClose() == null) {
      return null;
    }

    Double high = null;
    Double low = null;
    long volumeSum = 0L;
    boolean hasVolume = false;

    for (StockOhlcEntity candle : baseAsc) {
      if (candle == null) {
        continue;
      }
      if (candle.getHigh() != null) {
        high = (high == null) ? candle.getHigh() : Math.max(high, candle.getHigh());
      }
      if (candle.getLow() != null) {
        low = (low == null) ? candle.getLow() : Math.min(low, candle.getLow());
      }
      if (candle.getVolume() != null) {
        volumeSum += candle.getVolume();
        hasVolume = true;
      }
    }

    return toEntity(
        symbol,
        interval,
        windowStartEpochSec,
        first.getOpen(),
        high,
        low,
        last.getClose(),
        hasVolume ? volumeSum : null);
  }

  private StockOhlcEntity toEntity(
      String symbol,
      String interval,
      long ts,
      Double open,
      Double high,
      Double low,
      Double close,
      Long volume) {

    StockOhlcEntity entity = new StockOhlcEntity();
    entity.setSymbol(symbol);
    entity.setDataType(interval);
    entity.setTs(ts);
    entity.setOpen(open);
    entity.setHigh(high);
    entity.setLow(low);
    entity.setClose(close);
    entity.setVolume(volume);
    return entity;
  }

  private long alignToSessionWindow(long epochSec, long intervalSec) {
    long sessionStart = Instant.ofEpochSecond(epochSec)
        .atZone(NY)
        .toLocalDate()
        .atTime(SESSION_OPEN)
        .atZone(NY)
        .toEpochSecond();

    long offset = Math.max(0L, epochSec - sessionStart);
    return sessionStart + (offset / intervalSec) * intervalSec;
  }

  private long intervalToSeconds(String interval) {
    return switch (interval) {
      case "5m" -> 5L * 60;
      case "15m" -> 15L * 60;
      case "30m" -> 30L * 60;
      case "1h" -> 60L * 60;
      case "4h" -> 4L * 60 * 60;
      default -> -1L;
    };
  }

  private YahooQuoteDTO.QuoteDto fallbackQuoteFromDbLatest(String symbol, String interval) {
    YahooQuoteDTO.QuoteDto q = new YahooQuoteDTO.QuoteDto();
    q.symbol = symbol;

    StockOhlcEntity last = stockOhlcRepository.findTopBySymbolAndDataTypeOrderByTsDesc(symbol, interval);
    if (last != null) {
      q.price = last.getClose();
      q.marketTime = last.getTs();
    }
    return q;
  }

  private YahooQuoteDTO.QuoteDto toYahooQuote(CachedQuoteDto c) {
    YahooQuoteDTO.QuoteDto q = new YahooQuoteDTO.QuoteDto();
    q.symbol = c.getSymbol();
    q.price = c.getPrice();
    q.change = c.getChange();
    q.changePercent = c.getChangePercent();
    q.tradeVolume = c.getTradeVolume();
    q.marketTime = c.getMarketTime();
    q.marketState = c.getMarketState();
    q.open = c.getOpen();
    q.high = c.getHigh();
    q.low = c.getLow();
    q.prevClose = c.getPrevClose();
    return q;
  }
}
