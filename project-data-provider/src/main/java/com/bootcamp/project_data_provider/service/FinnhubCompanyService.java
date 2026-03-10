package com.bootcamp.project_data_provider.service;

import java.util.List;

import com.bootcamp.project_data_provider.dto.CompanyResponseDto;

public interface FinnhubCompanyService {
  
    CompanyResponseDto getCompany(String symbol);

    List<CompanyResponseDto> getCompanies(List<String> symbols);
}
