package com.idc.center.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.ServerAsset;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.IdcRule;
import com.idc.center.service.ServerService;

import jakarta.validation.Valid;

/**
 * 模块二：服务器资产。列表与筛选走 Query by Example。
 */
@RestController
@RequestMapping("/api")
public class ServerController {

    private final ServerService serverService;

    public ServerController(ServerService serverService) {
        this.serverService = serverService;
    }

    /** 多条件筛选：assetNo / brand / model / serialNo 模糊，status / cabinetId 精确，U 位与内存走范围 */
    @GetMapping("/servers")
    public ApiResponse<Views.PageResult<ServerAsset>> search(Dtos.ServerQuery query) {
        return ApiResponse.success(serverService.search(query));
    }

    @GetMapping("/servers/{id}")
    public ApiResponse<ServerAsset> get(@PathVariable("id") Long id) {
        return ApiResponse.success(serverService.get(id));
    }

    @PostMapping("/servers")
    public ApiResponse<ServerAsset> create(@Valid @RequestBody Dtos.ServerSaveReq req) {
        return ApiResponse.success("服务器资产已登记", serverService.create(req));
    }

    @PutMapping("/servers/{id}")
    public ApiResponse<ServerAsset> update(@PathVariable("id") Long id, @Valid @RequestBody Dtos.ServerSaveReq req) {
        return ApiResponse.success("服务器资产已更新", serverService.update(id, req));
    }

    /** 在架 / 下架 / 维修 / 报废 */
    @PostMapping("/servers/{id}/status")
    public ApiResponse<ServerAsset> changeStatus(@PathVariable("id") Long id,
                                                 @Valid @RequestBody Dtos.ServerStatusReq req) {
        return ApiResponse.success("状态已变更为「" + IdcRule.serverStatusName(req.getStatus()) + "」",
                serverService.changeStatus(id, req));
    }

    /** 下拉框：在架的服务器（发起下架/迁移工单时选） */
    @GetMapping("/servers/options")
    public ApiResponse<List<ServerAsset>> options() {
        return ApiResponse.success(serverService.options());
    }
}
