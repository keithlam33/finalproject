package com.bootcamp.project_stock_data.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bootcamp.project_stock_data.entity.StockProfileEntity;

public interface StockProfileRepository extends JpaRepository<StockProfileEntity, Long> {
  Optional<StockProfileEntity> findBySymbol(String symbol);

  List<StockProfileEntity> findBySymbolIn(Collection<String> symbols);
}
