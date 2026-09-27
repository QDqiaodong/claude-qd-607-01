package com.idc.center.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.EnergyRecord;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.EnergyService;

import jakarta.validation.Valid;

/**
 * 模块五：能耗记录（抄表 → 用电量 → 电费）。
 */
@RestController
@RequestMapping("/api")
public class EnergyController {

    private final EnergyService energyService;

    public EnergyController(EnergyService energyService) {
        this.energyService = energyService;
    }

    @GetMapping("/energy")
    public ApiResponse<Views.PageResult<EnergyRecord>> search(Dtos.EnergyQuery query) {
        return ApiResponse.success(energyService.search(query));
    }

    /** 顶部汇总卡：本月总用电 / 总电费 / 机柜用电排行 */
    @GetMapping("/energy/summary")
    public ApiResponse<Views.EnergySummary> summary(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResponse.success(energyService.summary(month));
    }

    @PostMapping("/energy")
    public ApiResponse<EnergyRecord> create(@Valid @RequestBody Dtos.EnergyCreateReq req) {
        return ApiResponse.success("抄表记录已登记", energyService.create(req));
    }
}
