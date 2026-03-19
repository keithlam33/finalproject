package com.bootcamp.project_stock_data.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stock_profile")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockProfileEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_id", nullable = false)
  private Long stockId;

  @Column(nullable = false, unique = true)
  private String symbol;

  @OneToOne
  @JoinColumn(name = "stock_id", referencedColumnName = "id", insertable = false, updatable = false)
  private StockEntity stock;

  @Column(name = "company_name")
  private String companyName;

  private String industry;

  @Column(name = "market_cap")
  private Double marketCap;

  private String logo;

  @Column(name = "date_update")
  private LocalDate dateUpdate;
}
