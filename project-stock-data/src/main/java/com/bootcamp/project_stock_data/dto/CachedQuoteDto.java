package com.bootcamp.project_stock_data.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedQuoteDto {
  private String symbol;

  private Double price;
  private Double change;
  private Double changePercent;

  private Double open;
  private Double high;
  private Double low;
  private Double prevClose;

  private Long tradeVolume;

  private String marketState;
  private Long marketTime; // epochSec from provider
}

