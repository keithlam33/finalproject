package com.bootcamp.project_stock_data.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bootcamp.project_stock_data.entity.StockProfileEntity;

public interface StockProfileRepository extends JpaRepository<StockProfileEntity, String>{
    
}
