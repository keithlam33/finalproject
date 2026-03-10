package com.bootcamp.project_data_provider.service;

import java.util.List;

import com.bootcamp.project_data_provider.dto.QuoteResponseDto;


public interface YahooQuoteService {
    QuoteResponseDto getQuote(List<String> symbols);
}
