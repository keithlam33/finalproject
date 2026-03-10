package com.bootcamp.project_stock_data.controller.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.bootcamp.project_stock_data.controller.HeatmapOperation;
import com.bootcamp.project_stock_data.dto.HeatmapDto;
import com.bootcamp.project_stock_data.service.HeatmapService;

@RestController
public class HeatmapController implements HeatmapOperation{
    @Autowired
    private HeatmapService heatmapService;

    @Override
    public List<HeatmapDto> getHeatmap() {
        return heatmapService.getHeatmap();
    }
}
