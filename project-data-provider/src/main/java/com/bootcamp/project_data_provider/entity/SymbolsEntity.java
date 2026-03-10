 
package com.bootcamp.project_data_provider.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "stocks")
@Getter
public class SymbolsEntity{

  @Id
  @Column(name = "symbol")
  private String symbol;

  @Column(name = "status")
  private Boolean status;

  
}

