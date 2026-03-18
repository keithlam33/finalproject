package com.bootcamp.project_stock_data.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bootcamp.project_stock_data.entity.StockEntity;

public interface StockRepository extends JpaRepository<StockEntity, Long> {
  List<StockEntity> findByStatusTrue();

  Optional<StockEntity> findBySymbol(String symbol);
}
