package com.bootcamp.project_data_provider.dto;


import lombok.Builder;
import lombok.Getter;
@Builder
@Getter
public class CompanyResponseDto {
  
    public String symbol;
    public String companyName;
    public String industry;
    public Double marketCap;
    public String logo;
   
  
}
