package com.bootcamp.project_stock_data.model.dto;


import lombok.Getter;


@Getter
public class FinnhubCompanyDTO {

    public String symbol;
    public String companyName;
    public String industry;
    public Double marketCap;
    public String logo;
    
}
