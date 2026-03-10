package com.bootcamp.project_stock_data.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stocks")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockEntity {
    @Id
    private String symbol;

    private Boolean status;

    @Column(name = "date_added")
    private LocalDate dateAdded;

    @Column(name = "date_removed")
    private LocalDate dateRemoved;
}
