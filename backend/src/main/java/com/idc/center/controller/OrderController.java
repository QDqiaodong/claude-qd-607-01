package com.idc.center.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.IdcRule;
import com.idc.center.service.OrderService;

import jakarta.validation.Valid;

/**
 * 模块三：上架 / 下架 / 迁移工单。
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public ApiResponse<Views.PageResult<WorkOrder>> search(Dtos.OrderQuery query) {
        return ApiResponse.success(orderService.search(query));
    }

    @PostMapping("/orders")
    public ApiResponse<WorkOrder> create(@Valid @RequestBody Dtos.OrderCreateReq req) {
        return ApiResponse.success("工单已提交，等待审批", orderService.create(req));
    }

    /** 推进：待审批 → 已批复 → 施工中 → 已完成 */
    @PostMapping("/orders/{id}/advance")
    public ApiResponse<WorkOrder> advance(@PathVariable("id") Long id) {
        WorkOrder order = orderService.advance(id);
        return ApiResponse.success("工单已推进到「" + IdcRule.orderStatusName(order.getStatus()) + "」", order);
    }

    /** 驳回：待审批 / 已批复 → 已驳回 */
    @PostMapping("/orders/{id}/reject")
    public ApiResponse<WorkOrder> reject(@PathVariable("id") Long id, @Valid @RequestBody Dtos.RejectReq req) {
        return ApiResponse.success("工单已驳回", orderService.reject(id, req));
    }

    @GetMapping("/servers/{id}/orders")
    public ApiResponse<List<WorkOrder>> byServer(@PathVariable("id") Long id) {
        return ApiResponse.success(orderService.byServer(id));
    }
}
