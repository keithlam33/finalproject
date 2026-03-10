package com.bootcamp.project_stock_data.mapper;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.bootcamp.project_stock_data.entity.StockProfileEntity;
import com.bootcamp.project_stock_data.model.dto.FinnhubCompanyDTO;

@Component
public class EntityMapper {
    public StockProfileEntity map(FinnhubCompanyDTO dto) {
    return StockProfileEntity.builder()
    .symbol(dto.getSymbol())
    .companyName(dto.getCompanyName())
    .industry(dto.getIndustry())
    .marketCap(dto.getMarketCap())
    .logo(dto.getLogo())
    .dateUpdate(LocalDate.now())
    .build();
}
}
