package com.bootcamp.project_data_provider.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bootcamp.project_data_provider.entity.SymbolsEntity;

@Repository
public interface SymbolsRepository extends JpaRepository<SymbolsEntity, String> {
  List<SymbolsEntity> findByStatusTrue();
}
