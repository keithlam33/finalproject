package com.bootcamp.project_stock_data.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.codelibrary.RedisManager;
import com.bootcamp.project_stock_data.dto.CachedQuoteDto;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.service.QuoteCacheService;

@Service
public class QuoteCacheServiceimpl implements QuoteCacheService {

  private static final Duration TTL = Duration.ofSeconds(30);
  private static final String KEY_PREFIX = "quote:latest:";

  @Autowired
  private RedisManager redisManager;

  @Override
  public void refreshQuotesToRedis(YahooQuoteDTO resp) {
    if (resp == null || resp.getQuotes() == null)
      return;

    for (YahooQuoteDTO.QuoteDto q : resp.getQuotes()) {
      if (q == null || q.getSymbol() == null)
        continue;

      String symbol = toInternalSymbol(q.getSymbol());
      if (symbol.isEmpty())
        continue;

      CachedQuoteDto dto = CachedQuoteDto.builder()
          .symbol(symbol)
          .price(q.getPrice())
          .change(q.getChange())
          .changePercent(q.getChangePercent())
          .open(q.getOpen())
          .high(q.getHigh())
          .low(q.getLow())
          .prevClose(q.getPrevClose())
          .tradeVolume(q.getTradeVolume())
          .marketState(q.getMarketState())
          .marketTime(q.getMarketTime())
          .build();

      redisManager.set(KEY_PREFIX + symbol, dto, TTL);
    }
  }

  @Override
  public CachedQuoteDto getLatestQuote(String symbol) {
    if (symbol == null)
      return null;
    String s = toInternalSymbol(symbol);
    if (s.isEmpty())
      return null;
    CachedQuoteDto exact = redisManager.get(KEY_PREFIX + s, CachedQuoteDto.class);
    if (exact != null) {
      return exact;
    }

    // Temporary compatibility fallback for any old cache entry written with Yahoo style.
    return redisManager.get(KEY_PREFIX + toYahooSymbol(s), CachedQuoteDto.class);
  }

  @Override
  public List<CachedQuoteDto> getLatestQuotes(List<String> symbols) {
    if (symbols == null || symbols.isEmpty())
      return List.of();

    List<CachedQuoteDto> out = new ArrayList<>();
    for (String s : symbols) {
      CachedQuoteDto q = getLatestQuote(s);
      if (q != null)
        out.add(q);
    }
    return out;
  }

  private String toInternalSymbol(String symbol) {
    return symbol.trim().toUpperCase().replace('-', '.');
  }

  private String toYahooSymbol(String symbol) {
    return symbol.replace(".", "-");
  }
}
