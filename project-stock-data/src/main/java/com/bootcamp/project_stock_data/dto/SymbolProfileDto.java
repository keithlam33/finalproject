package com.bootcamp.project_stock_data.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SymbolProfileDto {
  public String symbol;
  public String companyName;
  public String industry;
  public Double marketCap;
  public String logo;
}

