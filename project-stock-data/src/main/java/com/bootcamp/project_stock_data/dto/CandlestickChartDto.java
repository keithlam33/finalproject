package com.bootcamp.project_stock_data.dto;

import java.util.List;


import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CandlestickChartDto {
    public String symbol;
    public String companyName;
    public String industry;
    public String logo;

    public Double currentPrice;
    public Double change;
    public Double changePercent;

    public Double dayOpen;
    public Double dayHigh;
    public Double dayLow;
    public Double prevClose;
    public Long dayVolume;

    public String marketState;
    public Long marketTime; // epochSec (quote time from provider)
    public Long delaySec; // nowEpochSec - marketTime

    public String interval; // "1m"/"5m"/"1d"
    public List<CandleDto> candles;

    // history data from database
    @Getter
    @Builder
    public static class CandleDto {
        public Long epochSec;
        public Double open;
        public Double high;
        public Double low;
        public Double close;
        public Long volume;
    }

    
}
