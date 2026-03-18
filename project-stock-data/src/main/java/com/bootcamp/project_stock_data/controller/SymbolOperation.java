package com.bootcamp.project_stock_data.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;

import com.bootcamp.project_stock_data.dto.SymbolProfileDto;

public interface SymbolOperation {
  @GetMapping(value = "/symbols")
  List<SymbolProfileDto> getActiveSymbols();
}

