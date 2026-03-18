package com.bootcamp.project_stock_data.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.bootcamp.project_stock_data.entity.StockEntity;
import com.bootcamp.project_stock_data.exception.BusinessException;
import com.bootcamp.project_stock_data.exception.ExceptionDTO;
import com.bootcamp.project_stock_data.exception.SysEx;
import com.bootcamp.project_stock_data.model.dto.FinnhubCompanyDTO;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.repository.StockRepository;
import com.bootcamp.project_stock_data.service.DataProviderService;

import tools.jackson.databind.ObjectMapper;

@Service
public class DataProviderServiceimpl implements DataProviderService {

  @Value("${data-provider.scheme:http}")
  private String scheme;

  @Value("${data-provider.host:localhost}")
  private String host;

  @Value("${data-provider.port:8080}")
  private int port;

  @Value("${data-provider.uri.quote:/quote}")
  private String quotePath;

  @Value("${data-provider.uri.company:/company}")
  private String companyPath;

  @Value("${data-provider.uri.companies:/companies}")
  private String companiesPath;

  @Autowired
  private RestTemplate restTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private StockRepository stockRepository;

  @Override
  public YahooQuoteDTO getQuotesByActiveSymbols() {
    List<StockEntity> active = stockRepository.findByStatusTrue();

    String symbolsCsv = active.stream()
        .map(StockEntity::getSymbol)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.joining(","));

    if (symbolsCsv.isBlank()) {
      YahooQuoteDTO empty = new YahooQuoteDTO();
      empty.quotes = List.of();
      return empty;
    }

    String url = UriComponentsBuilder.newInstance()
        .scheme(scheme)
        .host(host)
        .port(port)
        .path(quotePath)
        .queryParam("symbol", symbolsCsv)
        .build()
        .toUriString();

    long t0 = System.nanoTime();
    YahooQuoteDTO out = getForObject(url, YahooQuoteDTO.class, "quote-by-active-symbols");
    long ms = (System.nanoTime() - t0) / 1_000_000;
    System.out.println("getQuotesByActiveSymbols (http only) = " + ms + " ms");
    return out;
  }

  @Override
  public YahooQuoteDTO getQuoteBySymbol(String symbol) {
    String normalized = normalizeRequiredSymbol(symbol);
    String url = UriComponentsBuilder.newInstance()
        .scheme(scheme)
        .host(host)
        .port(port)
        .path(quotePath)
        .queryParam("symbol", normalized)
        .build()
        .toUriString();
    return getForObject(url, YahooQuoteDTO.class, "quote-by-symbol");
  }

  @Override
  public FinnhubCompanyDTO getCompany(String symbol) {
    String normalized = normalizeRequiredSymbol(symbol);
    String url = UriComponentsBuilder.newInstance()
        .scheme(scheme)
        .host(host)
        .port(port)
        .path(companyPath)
        .queryParam("symbol", normalized)
        .build()
        .toUriString();
    return getForObject(url, FinnhubCompanyDTO.class, "company-by-symbol");
  }

  @Override
  public List<FinnhubCompanyDTO> getCompanies(List<String> symbols) {
    List<FinnhubCompanyDTO> list = new ArrayList<>();
    if (symbols == null || symbols.isEmpty()) {
      return list;
    }
    for (String symbol : symbols) {
      FinnhubCompanyDTO c = getCompany(symbol);
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

  private String normalizeRequiredSymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("Symbol is required.");
    }
    return symbol.trim().toUpperCase();
  }

  private <T> T getForObject(String url, Class<T> responseType, String operation) {
    try {
      T body = restTemplate.getForObject(url, responseType);
      if (body == null) {
        throw new BusinessException(
            SysEx.DATA_PROVIDER_RESPONSE_INVALID,
            SysEx.DATA_PROVIDER_RESPONSE_INVALID.getMessage() + " operation=" + operation + " Empty body.");
      }
      return body;
    } catch (HttpStatusCodeException ex) {
      throw mapHttpStatusException(ex, operation);
    } catch (ResourceAccessException ex) {
      throw new BusinessException(
          SysEx.DATA_PROVIDER_API_CALL_FAILED,
          SysEx.DATA_PROVIDER_API_CALL_FAILED.getMessage() + " operation=" + operation + " " + ex.getMessage());
    } catch (RestClientException ex) {
      throw new BusinessException(
          SysEx.DATA_PROVIDER_RESPONSE_INVALID,
          SysEx.DATA_PROVIDER_RESPONSE_INVALID.getMessage() + " operation=" + operation + " " + ex.getMessage());
    }
  }

  private BusinessException mapHttpStatusException(HttpStatusCodeException ex, String operation) {
    SysEx sysEx = ex.getStatusCode().is4xxClientError()
        ? SysEx.DATA_PROVIDER_REJECTED_REQUEST
        : SysEx.DATA_PROVIDER_API_CALL_FAILED;

    return new BusinessException(
        sysEx,
        sysEx.getMessage()
            + " operation=" + operation
            + " status=" + ex.getStatusCode()
            + extractUpstreamError(ex.getResponseBodyAsString()));
  }

  private String extractUpstreamError(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }

    try {
      ExceptionDTO dto = objectMapper.readValue(body, ExceptionDTO.class);
      if (dto.getCode() == null && (dto.getMessage() == null || dto.getMessage().isBlank())) {
        return " upstreamBody=" + body;
      }
      return " upstreamCode=" + dto.getCode() + " upstreamMessage=" + dto.getMessage();
    } catch (Exception e) {
      return " upstreamBody=" + body;
    }
  }

  public void benchQuotes(int n) {
    long sumMs = 0;
    long maxMs = 0;

    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      getQuotesByActiveSymbols();
      long ms = (System.nanoTime() - t0) / 1_000_000L;

      sumMs += ms;
      if (ms > maxMs)
        maxMs = ms;
      System.out.println("run " + (i + 1) + "/" + n + " = " + ms + " ms");
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    System.out.println("avg ms = " + (sumMs / (double) n));
    System.out.println("max ms = " + maxMs);
  }
}
