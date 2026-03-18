package com.bootcamp.project_data_provider.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.bootcamp.project_data_provider.dto.QuoteResponseDto;
import com.bootcamp.project_data_provider.exception.BusinessException;
import com.bootcamp.project_data_provider.exception.SysEx;
import com.bootcamp.project_data_provider.external.yahoo.YahooSessionManager;
import com.bootcamp.project_data_provider.mapper.DtoMapper;
import com.bootcamp.project_data_provider.model.dto.YahooQuoteDTO;
import com.bootcamp.project_data_provider.service.YahooQuoteService;

@Service
public class YahooQuoteServiceImpl implements YahooQuoteService {

  @Value("${external-api.yahoo.domain}")
  private String domain;

  @Value("${external-api.yahoo.uri.quote}")
  private String quotePath;

  @Autowired
  private RestTemplate restTemplate;

  @Autowired
  private YahooSessionManager yahooSessionManager;

  @Autowired
  private DtoMapper dtoMapper;

  @Override
  public QuoteResponseDto getQuote(List<String> symbols) {
    String csv = normalizeSymbols(symbols);
    if (csv.isBlank()) {
      throw new IllegalArgumentException("At least one symbol is required.");
    }

    String crumb = yahooSessionManager.getCrumb();
    String cookie = yahooSessionManager.getCookieHeader();

    String url = UriComponentsBuilder.newInstance()
        .scheme("https")
        .host(this.domain)
        .path(this.quotePath)
        .queryParam("symbols", csv)
        .queryParamIfPresent("crumb", (crumb == null || crumb.isBlank())
            ? java.util.Optional.empty()
            : java.util.Optional.of(crumb))
        .build(true)
        .toUriString();

    HttpHeaders headers = new HttpHeaders();
    if (cookie != null && !cookie.isBlank()) {
      headers.add("Cookie", cookie);
    }
    HttpEntity<Void> entity = new HttpEntity<>(headers);

    try {
      ResponseEntity<YahooQuoteDTO> resp = this.restTemplate.exchange(
          url, HttpMethod.GET, entity, YahooQuoteDTO.class);
      YahooQuoteDTO body = resp.getBody();
      if (body == null || body.getQuoteResponse() == null) {
        throw new BusinessException(
            SysEx.YAHOO_RESPONSE_INVALID,
            SysEx.YAHOO_RESPONSE_INVALID.getMessage() + " Empty response body.");
      }
      return dtoMapper.map(body);
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      yahooSessionManager.invalidate();
      throw new BusinessException(
          SysEx.YAHOO_API_CALL_FAILED,
          SysEx.YAHOO_API_CALL_FAILED.getMessage() + " status=" + ex.getStatusCode() + " message=" + ex.getMessage());
    } catch (ResourceAccessException ex) {
      yahooSessionManager.invalidate();
      throw new BusinessException(
          SysEx.YAHOO_API_CALL_FAILED,
          SysEx.YAHOO_API_CALL_FAILED.getMessage() + " " + ex.getMessage());
    } catch (RestClientException ex) {
      yahooSessionManager.invalidate();
      throw new BusinessException(
          SysEx.YAHOO_RESPONSE_INVALID,
          SysEx.YAHOO_RESPONSE_INVALID.getMessage() + " " + ex.getMessage());
    }
  }

  private static String normalizeSymbols(List<String> symbols) {
    if (symbols == null) {
      return "";
    }
    return symbols.stream()
        .filter(s -> s != null)
        .map(String::trim)
        .map(String::toUpperCase)
        .filter(s -> !s.isBlank())
        .map(YahooQuoteServiceImpl::toYahooSymbol)
        .collect(Collectors.joining(","));
  }

  private static String toYahooSymbol(String symbol) {
    return symbol.replace(".", "-");
  }
}
