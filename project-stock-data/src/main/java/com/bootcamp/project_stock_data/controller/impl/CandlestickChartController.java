package com.bootcamp.project_stock_data.controller.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.bootcamp.project_stock_data.controller.CandlestickChartOperation;
import com.bootcamp.project_stock_data.dto.CandlestickChartDto;
import com.bootcamp.project_stock_data.service.CandlestickChartService;

@RestController
public class CandlestickChartController implements CandlestickChartOperation {
  @Autowired
  private CandlestickChartService candlestickChartService;

  @Override
  public CandlestickChartDto getCandlestick(String symbol, String interval, Integer limit, Long beforeTs) {
    return candlestickChartService.getCandlestick(symbol, interval, limit, beforeTs);
  }
}
