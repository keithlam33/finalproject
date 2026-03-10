package com.bootcamp.project_stock_data.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class HeatmapDto {
    public String symbol;
    public String companyName;
    public String industry;
    public Double marketCap;
    public String logo;
    public Double changePercent;
    public Double price;
    public Double change;
    public String marketState;
    public Long marketTime; // epochSec (quote time from provider)
    public Long delaySec; // nowEpochSec - marketTime
  
}
