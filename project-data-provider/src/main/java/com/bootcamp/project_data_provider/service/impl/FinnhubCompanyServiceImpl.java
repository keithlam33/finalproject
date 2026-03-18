package com.bootcamp.project_data_provider.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.bootcamp.project_data_provider.dto.CompanyResponseDto;
import com.bootcamp.project_data_provider.exception.BusinessException;
import com.bootcamp.project_data_provider.exception.SysEx;
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
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("Symbol is required.");
    }

    String normalized = symbol.trim().toUpperCase();
    String url = UriComponentsBuilder.newInstance()
        .scheme("https")
        .host(this.domain)
        .path(this.profile2Path)
        .queryParam("symbol", normalized)
        .queryParam("token", this.token)
        .build()
        .toUriString();

    try {
      FinnhubProfile2DTO external = restTemplate.getForObject(url, FinnhubProfile2DTO.class);
      if (external == null || external.getTicker() == null || external.getTicker().isBlank()) {
        throw new BusinessException(
            SysEx.FINNHUB_RESPONSE_INVALID,
            SysEx.FINNHUB_RESPONSE_INVALID.getMessage() + " Empty response body for symbol=" + normalized);
      }
      return dtoMapper.map(external);
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      throw new BusinessException(
          SysEx.FINNHUB_API_CALL_FAILED,
          SysEx.FINNHUB_API_CALL_FAILED.getMessage() + " status=" + ex.getStatusCode() + " message=" + ex.getMessage());
    } catch (ResourceAccessException ex) {
      throw new BusinessException(
          SysEx.FINNHUB_API_CALL_FAILED,
          SysEx.FINNHUB_API_CALL_FAILED.getMessage() + " " + ex.getMessage());
    } catch (RestClientException ex) {
      throw new BusinessException(
          SysEx.FINNHUB_RESPONSE_INVALID,
          SysEx.FINNHUB_RESPONSE_INVALID.getMessage() + " " + ex.getMessage());
    }
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
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException(
            SysEx.THREAD_INTERRUPTED,
            SysEx.THREAD_INTERRUPTED.getMessage() + " symbol=" + symbol);
      }
    }
    return list;
  }
}
