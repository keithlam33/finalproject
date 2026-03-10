package com.bootcamp.project_data_provider.controller.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.bootcamp.project_data_provider.controller.FinnhubCompanyOperation;
import com.bootcamp.project_data_provider.dto.CompanyResponseDto;
import com.bootcamp.project_data_provider.service.FinnhubCompanyService;

@RestController
public class FinnhubCompanyController implements FinnhubCompanyOperation {

  @Autowired
  private FinnhubCompanyService finnhubCompanyService;

  @Override
  public CompanyResponseDto getCompany(String symbol) {
    return finnhubCompanyService.getCompany(symbol);
  }
  @Override
  public java.util.List<CompanyResponseDto> getCompanies(List<String> symbols) {
    return finnhubCompanyService.getCompanies(symbols);
  }
}
