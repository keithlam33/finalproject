package com.bootcamp.project_stock_data.controller.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.bootcamp.project_stock_data.controller.SymbolOperation;
import com.bootcamp.project_stock_data.dto.SymbolProfileDto;
import com.bootcamp.project_stock_data.service.SymbolService;

@RestController
public class SymbolController implements SymbolOperation {

  @Autowired
  private SymbolService symbolService;

  @Override
  public List<SymbolProfileDto> getActiveSymbols() {
    return symbolService.getActiveSymbols();
  }
}

