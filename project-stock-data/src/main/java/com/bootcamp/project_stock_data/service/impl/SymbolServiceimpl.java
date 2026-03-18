package com.bootcamp.project_stock_data.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.dto.SymbolProfileDto;
import com.bootcamp.project_stock_data.entity.StockEntity;
import com.bootcamp.project_stock_data.entity.StockProfileEntity;
import com.bootcamp.project_stock_data.repository.StockProfileRepository;
import com.bootcamp.project_stock_data.repository.StockRepository;
import com.bootcamp.project_stock_data.service.SymbolService;

@Service
public class SymbolServiceimpl implements SymbolService {

  @Autowired
  private StockRepository stockRepository;

  @Autowired
  private StockProfileRepository stockProfileRepository;

  @Override
  public List<SymbolProfileDto> getActiveSymbols() {
    List<StockEntity> active = stockRepository.findByStatusTrue();

    List<String> symbols = active.stream()
        .map(StockEntity::getSymbol)
        .filter(s -> s != null && !s.isBlank())
        .map(String::trim)
        .distinct()
        .sorted()
        .collect(Collectors.toList());

    Map<String, StockProfileEntity> profileMap = new HashMap<>();
    for (StockProfileEntity p : stockProfileRepository.findBySymbolIn(symbols)) {
      if (p != null && p.getSymbol() != null) {
        profileMap.put(p.getSymbol().trim(), p);
      }
    }

    return symbols.stream()
        .map(symbol -> {
          StockProfileEntity p = profileMap.get(symbol);
          return SymbolProfileDto.builder()
              .symbol(symbol)
              .companyName(p == null ? null : p.getCompanyName())
              .industry(p == null ? null : p.getIndustry())
              .marketCap(p == null ? null : p.getMarketCap())
              .logo(p == null ? null : p.getLogo())
              .build();
        })
        .collect(Collectors.toList());
  }
}
