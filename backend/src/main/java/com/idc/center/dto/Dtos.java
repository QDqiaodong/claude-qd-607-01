package com.idc.center.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 各模块的写请求体。字段一律不做默认值，默认值与业务校验都放在 Service 里。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 机房新增 */
    @Data
    public static class RoomSaveReq {
        @NotBlank(message = "机房编号不能为空")
        private String code;
        @NotBlank(message = "机房名称不能为空")
        private String name;
        private String floorNo;
        private BigDecimal areaSqm;
        private BigDecimal powerCapacity;
        private String dutyPerson;
        private String remark;
    }

    /** 机柜新增 */
    @Data
    public static class CabinetSaveReq {
        @NotNull(message = "请选择所属机房")
        private Long roomId;
        @NotBlank(message = "机柜编号不能为空")
        private String code;
        private String name;
        @NotNull(message = "行号不能为空")
        private Integer rowNo;
        @NotNull(message = "列号不能为空")
        private Integer colNo;
        @NotNull(message = "总 U 位数不能为空")
        @Min(value = 10, message = "总 U 位数不能少于 10U")
        @Max(value = 48, message = "总 U 位数不能多于 48U")
        private Integer totalU;
        private BigDecimal ratedPower;
        private String remark;
    }

    /** 服务器资产新增/修改 */
    @Data
    public static class ServerSaveReq {
        @NotBlank(message = "资产编号不能为空")
        private String assetNo;
        private String brand;
        private String model;
        private String serialNo;
        private String cpuModel;
        private Integer memoryGb;
        private String diskSpec;
        private String ipAddress;
        private String osVersion;
        private Long cabinetId;
        private Integer startU;
        private Integer sizeU;
        private String status;
        private String ownerName;
        private LocalDate buyDate;
        private String remark;
    }

    /** 服务器状态变更 */
    @Data
    public static class ServerStatusReq {
        @NotBlank(message = "状态不能为空")
        private String status;
    }

    /** 服务器查询条件（走 Query by Example） */
    @Data
    public static class ServerQuery {
        private String assetNo;
        private String brand;
        private String model;
        private String serialNo;
        private String status;
        private Long cabinetId;
        private Integer minStartU;
        private Integer maxStartU;
        private Integer minMemory;
        private int page = 0;
        private int size = 10;
    }

    /** 工单新增 */
    @Data
    public static class OrderCreateReq {
        @NotNull(message = "请选择服务器")
        private Long serverId;
        @NotBlank(message = "请选择工单类型")
        private String orderType;
        private Long cabinetId;
        private Integer targetU;
        @NotBlank(message = "申请人不能为空")
        private String applicant;
        private String remark;
    }

    /** 工单查询条件（走 Query by Example） */
    @Data
    public static class OrderQuery {
        private String orderNo;
        private String orderType;
        private String status;
        private String applicant;
        private Long cabinetId;
        private int page = 0;
        private int size = 10;
    }

    /** 驳回工单 */
    @Data
    public static class RejectReq {
        @NotBlank(message = "请填写驳回原因")
        private String reason;
    }

    /** 新增巡检 */
    @Data
    public static class InspectionCreateReq {
        @NotNull(message = "请选择机柜")
        private Long cabinetId;
        @NotBlank(message = "请填写巡检人")
        private String inspector;
        private LocalDateTime inspectTime;
        @NotNull(message = "请填写温度")
        private BigDecimal temperature;
        @NotNull(message = "请填写湿度")
        private BigDecimal humidity;
        private String powerStatus;
        private String description;
    }

    /** 巡检查询条件（走 Query by Example） */
    @Data
    public static class InspectionQuery {
        private String inspector;
        private Long cabinetId;
        private String powerStatus;
        private Integer abnormal;
        private Integer handled;
        private BigDecimal minTemp;
        private BigDecimal maxTemp;
        private BigDecimal minHumidity;
        private BigDecimal maxHumidity;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime to;
        private int page = 0;
        private int size = 10;
    }

    /** 异常闭环 */
    @Data
    public static class HandleReq {
        @NotBlank(message = "请填写处理说明")
        private String note;
    }

    /** 新增能耗抄表 */
    @Data
    public static class EnergyCreateReq {
        @NotNull(message = "请选择机柜")
        private Long cabinetId;
        @NotNull(message = "请选择抄表日期")
        private LocalDate meterDate;
        @NotNull(message = "请填写电表读数")
        private BigDecimal meterReading;
        private BigDecimal unitPrice;
        private String meterReader;
        private String remark;
    }

    /** 能耗查询条件（走 Query by Example） */
    @Data
    public static class EnergyQuery {
        private Long cabinetId;
        private String meterReader;
        /** 月份，格式 yyyy-MM */
        private String month;
        private BigDecimal minUsage;
        private BigDecimal maxUsage;
        private int page = 0;
        private int size = 10;
    }

    /** 新建停电检修隔离 */
    @Data
    public static class IsolationCreateReq {
        @NotNull(message = "请选择机柜")
        private Long cabinetId;
        @NotNull(message = "请选择计划开始时间")
        private LocalDateTime planStart;
        @NotNull(message = "请选择计划结束时间")
        private LocalDateTime planEnd;
        @NotBlank(message = "请填写检修原因")
        private String reason;
        @NotBlank(message = "请填写发起人")
        private String initiator;
        @NotBlank(message = "请填写现场负责人")
        private String manager;
    }

    /** 隔离台账查询条件（走 Query by Example） */
    @Data
    public static class IsolationQuery {
        private String isolationNo;
        private Long cabinetId;
        private String status;
        private int page = 0;
        private int size = 10;
    }

    /** 生效 / 取消的操作人（可空，空则记值班人员） */
    @Data
    public static class IsolationOperateReq {
        private String operator;
        private String reason;
    }

    /** 解除隔离：现场负责人 + 解除说明 */
    @Data
    public static class IsolationReleaseReq {
        @NotBlank(message = "请填写解除操作人")
        private String operator;
        @NotBlank(message = "请填写解除说明")
        private String note;
    }
}
