package com.bootcamp.project_stock_data.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;

import com.bootcamp.project_stock_data.dto.HeatmapDto;

public interface HeatmapOperation {
    @GetMapping(value = "/heatmap")
    List<HeatmapDto> getHeatmap();
}
