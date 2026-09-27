package com.idc.center.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.InspectionRecord;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.InspectionService;

import jakarta.validation.Valid;

/**
 * 模块四：巡检记录。
 */
@RestController
@RequestMapping("/api")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping("/inspections")
    public ApiResponse<Views.PageResult<InspectionRecord>> search(Dtos.InspectionQuery query) {
        return ApiResponse.success(inspectionService.search(query));
    }

    /** 顶部汇总：总数 / 异常数 / 未处理异常数 */
    @GetMapping("/inspections/summary")
    public ApiResponse<Views.InspectionSummary> summary() {
        return ApiResponse.success(inspectionService.summary());
    }

    @PostMapping("/inspections")
    public ApiResponse<InspectionRecord> create(@Valid @RequestBody Dtos.InspectionCreateReq req) {
        return ApiResponse.success("巡检记录已登记", inspectionService.create(req));
    }

    /** 异常闭环 */
    @PostMapping("/inspections/{id}/handle")
    public ApiResponse<InspectionRecord> handle(@PathVariable("id") Long id, @Valid @RequestBody Dtos.HandleReq req) {
        return ApiResponse.success("异常已标记处理", inspectionService.handle(id, req));
    }
}
