package com.bootcamp.project_stock_data.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.bootcamp.project_stock_data.entity.StockOhlcEntity;

public interface StockOhlcRepository extends JpaRepository<StockOhlcEntity, Long> {

  List<StockOhlcEntity> findBySymbolAndDataTypeOrderByTsAsc(String symbol, String dataType);
  List<StockOhlcEntity> findBySymbolAndDataTypeOrderByTsDesc(String symbol, String dataType, Pageable pageable);
  List<StockOhlcEntity> findBySymbolAndDataTypeAndTsLessThanOrderByTsDesc(
      String symbol, String dataType, Long ts, Pageable pageable);

  boolean existsBySymbolAndDataType(String symbol, String dataType);

  // Range query for aggregation (JPA Between is inclusive).
  List<StockOhlcEntity> findBySymbolAndDataTypeAndTsBetweenOrderByTsAsc(
      String symbol, String dataType, Long tsFrom, Long tsTo);

  StockOhlcEntity findTopBySymbolAndDataTypeOrderByTsDesc(String symbol, String dataType);

  StockOhlcEntity findBySymbolAndDataTypeAndTs(String symbol, String dataType, Long ts);

  StockOhlcEntity findTopBySymbolAndDataTypeAndTsBetweenOrderByTsDesc(
      String symbol, String dataType, Long tsFrom, Long tsTo);
}
