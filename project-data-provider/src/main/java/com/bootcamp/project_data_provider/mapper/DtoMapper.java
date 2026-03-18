package com.bootcamp.project_data_provider.mapper;

import java.util.List;
import org.springframework.stereotype.Component;

import com.bootcamp.project_data_provider.dto.CompanyResponseDto;
import com.bootcamp.project_data_provider.dto.QuoteResponseDto;
import com.bootcamp.project_data_provider.model.dto.FinnhubProfile2DTO;
import com.bootcamp.project_data_provider.model.dto.YahooQuoteDTO;



@Component
public class DtoMapper {

    public CompanyResponseDto map(FinnhubProfile2DTO profile) {
        if (profile == null) {
            return null;
        }
        return CompanyResponseDto.builder()
                .symbol(profile.getTicker())
                .companyName(profile.getName())
                .industry(profile.getFinnhubIndustry())
                .marketCap(profile.getMarketCapitalization())
                .logo(profile.getLogo())
                .build();
    }

    public QuoteResponseDto map(YahooQuoteDTO quote) {
    if (quote == null || quote.getQuoteResponse() == null
        || quote.getQuoteResponse().getResult() == null) {
        return QuoteResponseDto.builder()
            .quotes(java.util.Collections.emptyList())
            .build();
    }

    List<QuoteResponseDto.QuoteDto> list = quote.getQuoteResponse().getResult().stream()
        .filter(r -> r != null)
        .map(r -> QuoteResponseDto.QuoteDto.builder()
            .symbol(toInternalSymbol(r.getSymbol()))
            .price(r.getRegularMarketPrice())
            .change(r.getRegularMarketChange())
            .changePercent(r.getRegularMarketChangePercent())
            .tradeVolume(r.getRegularMarketVolume())
            .marketTime(r.getRegularMarketTime())
            .open(r.getRegularMarketOpen())
            .high(r.getRegularMarketDayHigh())
            .low(r.getRegularMarketDayLow())
            .prevClose(r.getRegularMarketPreviousClose())
            .marketState(r.getMarketState())
            .build())
        .collect(java.util.stream.Collectors.toList());

    return QuoteResponseDto.builder()
        .quotes(list)
        .build();
}

    private String toInternalSymbol(String symbol) {
    if (symbol == null) {
        return null;
    }
    return symbol.trim().toUpperCase().replace('-', '.');
}

}
