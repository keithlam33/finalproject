package com.bootcamp.project_data_provider.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bootcamp.project_data_provider.dto.CompanyResponseDto;

public interface FinnhubCompanyOperation {
    @GetMapping(value= "/company")
    CompanyResponseDto getCompany(@RequestParam(value = "symbol") String symbol);
    @GetMapping(value= "/companies")
    List<CompanyResponseDto> getCompanies(@RequestParam(value = "symbols") List<String> symbols);
}
