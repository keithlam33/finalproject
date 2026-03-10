package com.bootcamp.project_stock_data.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.dto.CachedQuoteDto;
import com.bootcamp.project_stock_data.dto.HeatmapDto;
import com.bootcamp.project_stock_data.entity.StockEntity;
import com.bootcamp.project_stock_data.entity.StockProfileEntity;
import com.bootcamp.project_stock_data.mapper.DtoMapper;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.repository.StockProfileRepository;
import com.bootcamp.project_stock_data.repository.StockRepository;
import com.bootcamp.project_stock_data.service.HeatmapService;
import com.bootcamp.project_stock_data.service.QuoteCacheService;

@Service
public class HeatmapServiceimpl implements HeatmapService {

  @Autowired
  private QuoteCacheService quoteCacheService;

  @Autowired
  private StockRepository stockRepository;

  @Autowired
  private StockProfileRepository stockProfileRepository;

  @Autowired
  private DtoMapper dtoMapper;

  @Override
  public List<HeatmapDto> getHeatmap() {
    List<StockEntity> active = stockRepository.findByStatusTrue();

    return active.stream()
        .map(s -> {
          if (s == null || s.getSymbol() == null)
            return null;

          String symbol = s.getSymbol().trim();
          if (symbol.isEmpty())
            return null;

          StockProfileEntity profile = stockProfileRepository.findById(symbol).orElse(null);
          if (profile == null)
            return null;

          CachedQuoteDto cached = quoteCacheService.getLatestQuote(symbol);
          if (cached == null)
            return null; // cache miss: you can choose to return a DTO with null price instead

          YahooQuoteDTO.QuoteDto quote = toYahooQuote(cached);
          return dtoMapper.map(profile, quote);
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
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
