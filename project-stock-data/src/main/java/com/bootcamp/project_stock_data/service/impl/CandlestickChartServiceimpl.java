package com.bootcamp.project_stock_data.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import com.bootcamp.project_stock_data.service.QuoteCacheService;

@Service
public class CandlestickChartServiceimpl implements CandlestickChartService {

  @Autowired
  private QuoteCacheService quoteCacheService;

  @Autowired
  private StockProfileRepository stockProfileRepository;

  @Autowired
  private StockOhlcRepository stockOhlcRepository;

  @Autowired
  private DtoMapper dtoMapper;

  @Override
  public CandlestickChartDto getCandlestick(String symbol, String interval, Integer limit, Long beforeTs) {
    if (symbol == null || symbol.isBlank())
      return null;

    String s = symbol.trim();
    String i = interval == null ? null : interval.trim();
    if (i == null || i.isEmpty())
      return null;

    StockProfileEntity profile = stockProfileRepository.findById(s).orElse(null);
    List<StockOhlcEntity> candles = findCandles(s, i, limit, beforeTs);

    CachedQuoteDto cached = quoteCacheService.getLatestQuote(s);

    // If cache miss, build a minimal quote from DB latest candle close (so endpoint still returns something).
    YahooQuoteDTO.QuoteDto quote = (cached != null) ? toYahooQuote(cached) : fallbackQuoteFromDbLatest(s, i);

    return dtoMapper.map(profile, quote, candles, i);
  }

  private List<StockOhlcEntity> findCandles(String symbol, String interval, Integer limit, Long beforeTs) {
    if (limit == null || limit <= 0) {
      return stockOhlcRepository.findBySymbolAndDataTypeOrderByTsAsc(symbol, interval);
    }

    Pageable pageable = PageRequest.of(0, limit);
    List<StockOhlcEntity> desc = (beforeTs == null)
        ? stockOhlcRepository.findBySymbolAndDataTypeOrderByTsDesc(symbol, interval, pageable)
        : stockOhlcRepository.findBySymbolAndDataTypeAndTsLessThanOrderByTsDesc(symbol, interval, beforeTs, pageable);

    List<StockOhlcEntity> asc = new ArrayList<>(desc);
    Collections.reverse(asc);
    return asc;
  }

  private YahooQuoteDTO.QuoteDto fallbackQuoteFromDbLatest(String symbol, String interval) {
    YahooQuoteDTO.QuoteDto q = new YahooQuoteDTO.QuoteDto();
    q.symbol = symbol;

    StockOhlcEntity last = stockOhlcRepository.findTopBySymbolAndDataTypeOrderByTsDesc(symbol, interval);
    if (last != null) {
      q.price = last.getClose();
      q.marketTime = last.getTs(); // best-effort: use bar time as quote time
    }
    return q; // other fields null OK
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
