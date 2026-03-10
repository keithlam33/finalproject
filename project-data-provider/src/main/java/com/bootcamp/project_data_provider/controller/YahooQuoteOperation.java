package com.bootcamp.project_data_provider.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bootcamp.project_data_provider.dto.QuoteResponseDto;

public interface YahooQuoteOperation {

  @GetMapping(value = "/quote")
  QuoteResponseDto getQuote(@RequestParam(value = "symbol") List<String> symbols);
}
