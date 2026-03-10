package com.bootcamp.project_data_provider.model.dto;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

import java.util.List;

@Getter
public class YahooQuoteDTO {

  @JsonProperty("quoteResponse")
  public QuoteResponse quoteResponse;

  @Getter
  public static class QuoteResponse {
    @JsonProperty("result")
    public List<Result> result;

    @JsonProperty("error")
    public Object error;
  }

  @Getter
  public static class Result {
    public String language;
    public String region;
    public String quoteType;
    public String typeDisp;
    public String quoteSourceName;

    public Boolean triggerable;
    public String customPriceAlertConfidence;

    public String currency;
    public String marketState;

    public Double regularMarketChangePercent;
    public Double regularMarketPrice;
    public Double regularMarketChange;

    public Boolean hasPrePostMarketData;

    public Long firstTradeDateMilliseconds;

    public List<Object> corporateActions;

    public Long postMarketTime;
    public Long regularMarketTime;

    public String exchange;
    public String messageBoardId;

    public String exchangeTimezoneName;
    public String exchangeTimezoneShortName;
    public Long gmtOffSetMilliseconds;

    public String market;
    public Boolean esgPopulated;

    public Integer priceHint;

    public Double postMarketChangePercent;
    public Double postMarketPrice;
    public Double postMarketChange;

    public Double regularMarketDayHigh;
    public String regularMarketDayRange;
    public Double regularMarketDayLow;

    public Long regularMarketVolume;
    public Double regularMarketPreviousClose;

    public Double bid;
    public Double ask;
    public Integer bidSize;
    public Integer askSize;

    public String fullExchangeName;
    public String financialCurrency;

    public Double regularMarketOpen;

    public Long averageDailyVolume3Month;
    public Long averageDailyVolume10Day;

    public Double fiftyTwoWeekLowChange;
    public Double fiftyTwoWeekLowChangePercent;
    public String fiftyTwoWeekRange;

    public Double fiftyTwoWeekHighChange;
    public Double fiftyTwoWeekHighChangePercent;

    public Double fiftyTwoWeekLow;
    public Double fiftyTwoWeekHigh;

    public Double fiftyTwoWeekChangePercent;

    public Long earningsTimestamp;
    public Long earningsTimestampStart;
    public Long earningsTimestampEnd;

    public Long earningsCallTimestampStart;
    public Long earningsCallTimestampEnd;

    public Boolean isEarningsDateEstimate;

    public Double trailingAnnualDividendRate;
    public Double trailingPE;
    public Double trailingAnnualDividendYield;

    public Double epsTrailingTwelveMonths;
    public Double epsForward;
    public Double epsCurrentYear;

    public Double priceEpsCurrentYear;

    public Long sharesOutstanding;

    public Double bookValue;

    public Double fiftyDayAverage;
    public Double fiftyDayAverageChange;
    public Double fiftyDayAverageChangePercent;

    public Double twoHundredDayAverage;
    public Double twoHundredDayAverageChange;
    public Double twoHundredDayAverageChangePercent;

    public Long marketCap;

    public Double forwardPE;
    public Double priceToBook;

    public Integer sourceInterval;
    public Integer exchangeDataDelayedBy;

    public String averageAnalystRating;

    public Boolean tradeable;
    public Boolean cryptoTradeable;

    public String shortName;
    public String longName;
    public String displayName;

    public String symbol;
  }
}
