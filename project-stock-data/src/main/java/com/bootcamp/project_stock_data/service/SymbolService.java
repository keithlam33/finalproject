package com.bootcamp.project_stock_data.service;

import java.util.List;

import com.bootcamp.project_stock_data.dto.SymbolProfileDto;

public interface SymbolService {
  List<SymbolProfileDto> getActiveSymbols();
}

