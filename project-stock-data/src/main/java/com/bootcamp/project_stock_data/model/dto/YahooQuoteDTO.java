package com.bootcamp.project_stock_data.model.dto;

import java.util.List;


import lombok.Getter;

@Getter
public class YahooQuoteDTO {
    
    public List<QuoteDto> quotes;
    @Getter
    public static class QuoteDto {
        public String symbol;
        public String marketState; // e.g. "REGULAR", "PRE", "POST", "CLOSED"
        public Double price;
        public Double change;
        public Double changePercent;
        public Long tradeVolume;
        public Long marketTime;
        public Double open;          
        public Double high;       
        public Double low;  
        public Double prevClose;
    }
    
}
