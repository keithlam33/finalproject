package com.bootcamp.project_stock_data.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.bootcamp.project_stock_data.entity.StockEntity;
import com.bootcamp.project_stock_data.model.dto.FinnhubCompanyDTO;
import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.repository.StockRepository;
import com.bootcamp.project_stock_data.service.DataProviderService;

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
  private StockRepository stockRepository;

  private String buildUrl(String path) {
    return UriComponentsBuilder.newInstance()
        .scheme(this.scheme)
        .host(this.host)
        .port(this.port)
        .path(path)
        .build()
        .toUriString();
  }

  @Override
  public YahooQuoteDTO getQuotesByActiveSymbols() {
    List<StockEntity> active = stockRepository.findByStatusTrue();

    String symbolsCsv = active.stream()
        .map(StockEntity::getSymbol)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.joining(","));

        String url = UriComponentsBuilder.newInstance()
        .scheme(scheme)      // "http"
        .host(host)          // "localhost" (唔好寫 localhost:8080)
        .port(port)          // 8080
        .path(quotePath)     // "/quote"
        .queryParam("symbol", symbolsCsv)
        .build()
        .toUriString();


    long t0 = System.nanoTime();
    YahooQuoteDTO out = restTemplate.getForObject(url, YahooQuoteDTO.class);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    System.out.println("getQuotesByActiveSymbols (http only) = " + ms + " ms");
    return out;
  }

  @Override
  public YahooQuoteDTO getQuoteBySymbol(String symbol) {
    String url = UriComponentsBuilder.newInstance()
        .scheme(scheme)
        .host(host)
        .port(port)
        .path(quotePath)
        .queryParam("symbol", symbol)
        .build()
        .toUriString();
    return restTemplate.getForObject(url, YahooQuoteDTO.class);
  }

  @Override
  public FinnhubCompanyDTO getCompany(String symbol) {
    String url = UriComponentsBuilder.newInstance()
        .scheme(scheme)
        .host(host)
        .port(port)
        .path(companyPath)
        .queryParam("symbol", symbol == null ? "" : symbol.trim())
        .build()
        .toUriString();
    return restTemplate.getForObject(url, FinnhubCompanyDTO.class);
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
        Thread.sleep(1000); // 1 call per second
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return list;
  }

  public void benchQuotes(int n) {
    long sumMs = 0;
    long maxMs = 0;

    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      getQuotesByActiveSymbols(); // 包括 DB 撈 active symbols + HTTP
      long ms = (System.nanoTime() - t0) / 1_000_000L;

      sumMs += ms;
      if (ms > maxMs) maxMs = ms;
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
