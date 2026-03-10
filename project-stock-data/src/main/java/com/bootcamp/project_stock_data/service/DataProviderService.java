package com.bootcamp.project_stock_data.service;


import java.util.List;

import com.bootcamp.project_stock_data.model.dto.FinnhubCompanyDTO;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;

public interface DataProviderService {
    YahooQuoteDTO getQuotesByActiveSymbols();
    YahooQuoteDTO getQuoteBySymbol(String symbol);
    FinnhubCompanyDTO getCompany(String symbol);
    List<FinnhubCompanyDTO> getCompanies(List<String> symbols);
}
