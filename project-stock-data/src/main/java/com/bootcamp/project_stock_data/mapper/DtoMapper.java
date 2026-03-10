package com.bootcamp.project_stock_data.mapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.bootcamp.project_stock_data.dto.CandlestickChartDto;
import com.bootcamp.project_stock_data.dto.CandlestickChartDto.CandleDto;
import com.bootcamp.project_stock_data.dto.HeatmapDto;
import com.bootcamp.project_stock_data.entity.StockOhlcEntity;
import com.bootcamp.project_stock_data.entity.StockProfileEntity;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;

@Component
public class DtoMapper {

  // Heatmap: profile + quote
  public HeatmapDto map(StockProfileEntity profile, YahooQuoteDTO.QuoteDto quote) {
    if (profile == null || quote == null)
      return null;

    Long delaySec = quote.getMarketTime() == null ? null
        : (System.currentTimeMillis() / 1000 - quote.getMarketTime());

    return HeatmapDto.builder()
        .symbol(profile.getSymbol())
        .companyName(profile.getCompanyName())
        .industry(profile.getIndustry())
        .marketCap(profile.getMarketCap())
        .logo(profile.getLogo())
        .price(quote.getPrice())
        .change(quote.getChange())
        .changePercent(quote.getChangePercent())
        .marketState(quote.getMarketState())
        .marketTime(quote.getMarketTime())
        .delaySec(delaySec)
        .build();
  }

  // Candlestick: profile + quote + ohlc (NO indicators)
  public CandlestickChartDto map(
      StockProfileEntity profile,
      YahooQuoteDTO.QuoteDto quote,
      List<StockOhlcEntity> ohlcList,
      String interval) {

    if (profile == null || quote == null)
      return null;

    Long delaySec = quote.getMarketTime() == null ? null
        : (System.currentTimeMillis() / 1000 - quote.getMarketTime());

    List<StockOhlcEntity> safe = (ohlcList == null) ? Collections.emptyList() : ohlcList;

    List<CandleDto> candles = safe.stream()
        .map(o -> CandleDto.builder()
            .epochSec(o.getTs())
            .open(o.getOpen())
            .high(o.getHigh())
            .low(o.getLow())
            .close(o.getClose())
            .volume(o.getVolume())
            .build())
        .collect(Collectors.toList());

    return CandlestickChartDto.builder()
        .symbol(profile.getSymbol())
        .companyName(profile.getCompanyName())
        .industry(profile.getIndustry())
        .currentPrice(quote.getPrice())
        .change(quote.getChange())
        .changePercent(quote.getChangePercent())
        .dayOpen(quote.getOpen())
        .dayHigh(quote.getHigh())
        .dayLow(quote.getLow())
        .prevClose(quote.getPrevClose())
        .marketState(quote.getMarketState())
        .marketTime(quote.getMarketTime())
        .delaySec(delaySec)
        .interval(interval)
        .candles(candles)
        .build();
  }
}
