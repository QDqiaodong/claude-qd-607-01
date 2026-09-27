package com.idc.center.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Room;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.CabinetService;

import jakarta.validation.Valid;

/**
 * 模块一：机房 / 机柜 / 机架 U 位图。
 */
@RestController
@RequestMapping("/api")
public class CabinetController {

    private final CabinetService cabinetService;

    public CabinetController(CabinetService cabinetService) {
        this.cabinetService = cabinetService;
    }

    @GetMapping("/rooms")
    public ApiResponse<List<Room>> listRooms() {
        return ApiResponse.success(cabinetService.listRooms());
    }

    @PostMapping("/rooms")
    public ApiResponse<Room> createRoom(@Valid @RequestBody Dtos.RoomSaveReq req) {
        return ApiResponse.success("机房已创建", cabinetService.createRoom(req));
    }

    @GetMapping("/cabinets")
    public ApiResponse<List<Views.CabinetView>> listCabinets(
            @RequestParam(value = "roomId", required = false) Long roomId) {
        return ApiResponse.success(cabinetService.listCabinets(roomId));
    }

    @PostMapping("/cabinets")
    public ApiResponse<Cabinet> createCabinet(@Valid @RequestBody Dtos.CabinetSaveReq req) {
        return ApiResponse.success("机柜已创建", cabinetService.createCabinet(req));
    }

    /** 某机柜的机架 U 位图：42U 画到 1U，服务器占几个 U 就跨几格 */
    @GetMapping("/cabinets/{id}/rack")
    public ApiResponse<Views.RackView> rack(@PathVariable("id") Long id) {
        return ApiResponse.success(cabinetService.rack(id));
    }
}
