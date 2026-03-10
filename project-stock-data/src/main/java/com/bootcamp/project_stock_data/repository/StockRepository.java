package com.bootcamp.project_stock_data.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bootcamp.project_stock_data.entity.StockEntity;

public interface StockRepository extends JpaRepository<StockEntity, String> {
    List<StockEntity> findByStatusTrue(); // 取得 active symbols
}
