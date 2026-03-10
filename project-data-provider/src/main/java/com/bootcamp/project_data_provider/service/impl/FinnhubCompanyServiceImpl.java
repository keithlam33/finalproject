package com.bootcamp.project_data_provider.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.bootcamp.project_data_provider.dto.CompanyResponseDto;
import com.bootcamp.project_data_provider.mapper.DtoMapper;
import com.bootcamp.project_data_provider.model.dto.FinnhubProfile2DTO;
import com.bootcamp.project_data_provider.service.FinnhubCompanyService;

@Service
public class FinnhubCompanyServiceImpl implements FinnhubCompanyService {

  @Value("${external-api.finnhub.domain}")
  private String domain;

  @Value("${external-api.finnhub.uri.profile2}")
  private String profile2Path;

  @Value("${external-api.finnhub.token}")
  private String token;

  @Autowired
  private RestTemplate restTemplate;

  @Autowired
  private DtoMapper dtoMapper;

  @Override
  public CompanyResponseDto getCompany(String symbol) {
    String normalized = symbol.trim().toUpperCase();
    String url = UriComponentsBuilder.newInstance()
        .scheme("https")
        .host(this.domain)
        .path(this.profile2Path)
        .queryParam("symbol", normalized)
        .queryParam("token", this.token)
        .build()
        .toUriString();
    FinnhubProfile2DTO external = restTemplate.getForObject(url, FinnhubProfile2DTO.class);
    return dtoMapper.map(external);
  }

  @Override
  public List<CompanyResponseDto> getCompanies(List<String> symbols) {
    List<CompanyResponseDto> list = new java.util.ArrayList<>();
    if (symbols == null) {
      return list;
    }

    for (String symbol : symbols) {
      CompanyResponseDto c = getCompany(symbol); 
      list.add(c);
      try {
        Thread.sleep(1000); // 瘥? 1 甈?
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return list;
  }
}
