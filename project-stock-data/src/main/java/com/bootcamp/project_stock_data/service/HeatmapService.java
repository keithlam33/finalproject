package com.bootcamp.project_stock_data.service;

import java.util.List;

import com.bootcamp.project_stock_data.dto.HeatmapDto;

public interface HeatmapService {
    List<HeatmapDto> getHeatmap();
    
}
