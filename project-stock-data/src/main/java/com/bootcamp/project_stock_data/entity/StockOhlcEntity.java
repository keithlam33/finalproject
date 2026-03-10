package com.bootcamp.project_stock_data.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stock_ohlc")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockOhlcEntity {
    @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String symbol;

  @Column(name = "data_type")
  private String dataType;

  @Column(name = "ts")
  private Long ts; // epochSec

  private Double open;
  private Double high;
  private Double low;
  private Double close;
  private Long volume;

  @Column(name = "date_update")
  private LocalDateTime dateUpdate;
   @ManyToOne
  @JoinColumn(name="symbol", referencedColumnName = "symbol", insertable=false, updatable=false)
  @Setter
  private StockEntity stock;
}
