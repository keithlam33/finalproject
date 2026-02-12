package main.java.com.bootcamp.final_project.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;

@Getter

public class ProfileDTO {
  private String country;
  private String currency;
  private String estimateCurrency;
  private String finnhubIndustry;
  private LocalDate ipo;
  private String logo;
  private BigDecimal marketCapitalization;
  private String name;
  private String phone;
  private String shareOutstanding;
  private String ticker;
  private String weburl;

}
