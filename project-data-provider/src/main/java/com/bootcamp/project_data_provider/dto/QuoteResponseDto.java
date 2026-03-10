package com.bootcamp.project_data_provider.dto;


import java.util.List;

import lombok.Builder;
import lombok.Getter;
@Getter
@Builder
public class QuoteResponseDto {
    public List<QuoteDto> quotes;
    @Getter
    @Builder
    public static class QuoteDto {
        public String symbol;
        public Double price;
        public Double change;
        public Double changePercent;
        public Long tradeVolume;
        public Long marketTime;
        public Double open;          
        public Double high;       
        public Double low; 
        public Double prevClose; 
        public String marketState;   
    }
    
  
}
