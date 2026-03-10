package com.bootcamp.project_stock_data.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bootcamp.project_stock_data.dto.CandlestickChartDto;

public interface CandlestickChartOperation {
  @GetMapping(value = "/candlestick")
  CandlestickChartDto getCandlestick(
      @RequestParam String symbol,
      @RequestParam String interval,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long beforeTs);
}
