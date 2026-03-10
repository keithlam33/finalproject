package com.bootcamp.project_stock_data.service;

import com.bootcamp.project_stock_data.dto.CandlestickChartDto;

public interface CandlestickChartService {
  CandlestickChartDto getCandlestick(String symbol, String interval, Integer limit, Long beforeTs);
}
