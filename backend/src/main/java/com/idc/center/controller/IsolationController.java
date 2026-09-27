package com.idc.center.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.Isolation;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.IdcRule;
import com.idc.center.service.IsolationService;

import jakarta.validation.Valid;

/**
 * 模块六：机柜停电检修隔离。台账 / 详情 / 生效 / 取消 / 解除。
 */
@RestController
@RequestMapping("/api")
public class IsolationController {

    private final IsolationService isolationService;

    public IsolationController(IsolationService isolationService) {
        this.isolationService = isolationService;
    }

    /** 台账：状态 / 机柜 / 单号筛选，分页 */
    @GetMapping("/isolations")
    public ApiResponse<Views.PageResult<Views.IsolationView>> search(Dtos.IsolationQuery query) {
        return ApiResponse.success(isolationService.search(query));
    }

    /** 全部隔离中的记录（工单页标记冻结用） */
    @GetMapping("/isolations/active")
    public ApiResponse<List<Views.IsolationView>> activeList() {
        return ApiResponse.success(isolationService.activeList());
    }

    /** 详情：计划信息 + 快照 + 当前差异 + 冲突施工 + 操作记录 */
    @GetMapping("/isolations/{id}")
    public ApiResponse<Views.IsolationDetail> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(isolationService.detail(id));
    }

    @PostMapping("/isolations")
    public ApiResponse<Isolation> create(@Valid @RequestBody Dtos.IsolationCreateReq req) {
        return ApiResponse.success("隔离记录已创建，待生效", isolationService.create(req));
    }

    /** 生效：固定影响清单 + 冻结施工；可重复提交（幂等） */
    @PostMapping("/isolations/{id}/activate")
    public ApiResponse<Isolation> activate(@PathVariable("id") Long id,
                                           @RequestBody(required = false) Dtos.IsolationOperateReq req) {
        String operator = req == null ? null : req.getOperator();
        Isolation iso = isolationService.activate(id, operator);
        return ApiResponse.success("隔离已生效（" + IdcRule.isolationStatusName(iso.getStatus()) + "）", iso);
    }

    /** 取消：只有待生效可以取消 */
    @PostMapping("/isolations/{id}/cancel")
    public ApiResponse<Isolation> cancel(@PathVariable("id") Long id,
                                         @RequestBody(required = false) Dtos.IsolationOperateReq req) {
        return ApiResponse.success("隔离记录已取消", isolationService.cancel(id, req));
    }

    /** 解除：现场负责人填写解除说明后收口；可安全重试 */
    @PostMapping("/isolations/{id}/release")
    public ApiResponse<Isolation> release(@PathVariable("id") Long id,
                                          @Valid @RequestBody Dtos.IsolationReleaseReq req) {
        return ApiResponse.success("隔离已解除，相关施工恢复正常推进", isolationService.release(id, req));
    }

    /** 机柜当前生效中的隔离（机柜页横幅用），无则 data 为 null */
    @GetMapping("/cabinets/{id}/isolation")
    public ApiResponse<Views.IsolationView> currentByCabinet(@PathVariable("id") Long id) {
        return ApiResponse.success(isolationService.currentByCabinet(id));
    }
}
