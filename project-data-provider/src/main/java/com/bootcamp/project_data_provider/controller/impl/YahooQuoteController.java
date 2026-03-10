package com.bootcamp.project_data_provider.controller.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.bootcamp.project_data_provider.controller.YahooQuoteOperation;
import com.bootcamp.project_data_provider.dto.QuoteResponseDto;
import com.bootcamp.project_data_provider.service.YahooQuoteService;

@RestController
public class YahooQuoteController implements YahooQuoteOperation {

  @Autowired
  private YahooQuoteService yahooQuoteService;

  @Override
  public QuoteResponseDto getQuote(List<String> symbols) {
    return yahooQuoteService.getQuote(symbols);
  }
  
}
