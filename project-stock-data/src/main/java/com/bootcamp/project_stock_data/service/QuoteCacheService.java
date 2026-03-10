package com.bootcamp.project_stock_data.service;

import java.util.List;

import com.bootcamp.project_stock_data.dto.CachedQuoteDto;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;

public interface QuoteCacheService {

  // Scheduler pulls provider once, then passes the response here to cache into Redis.
  void refreshQuotesToRedis(YahooQuoteDTO resp);

  // Consumer reads from redis
  CachedQuoteDto getLatestQuote(String symbol);

  List<CachedQuoteDto> getLatestQuotes(List<String> symbols);
}
