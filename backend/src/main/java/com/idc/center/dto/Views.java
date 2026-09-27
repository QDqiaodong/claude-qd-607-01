package com.idc.center.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.IsolationEvent;
import com.idc.center.domain.ServerAsset;

import lombok.Data;

/**
 * 读模型：把「给页面看」的组合数据在这里拼好，Controller 直接返回。
 */
public final class Views {

    private Views() {
    }

    /** 机架图上的一个格子：占 1U 或多 U（服务器占几个 U 就跨几格） */
    @Data
    public static class RackSlot {
        /** 格子顶部 U 位（渲染时按 U 位倒序，从 42U 画到 1U） */
        private Integer topU;
        /** 起始 U 位（最下面那格） */
        private Integer startU;
        private Integer sizeU;
        /** FREE / OCCUPIED / REPAIR / SCRAP */
        private String state;
        private Long serverId;
        private String assetNo;
        private String brand;
        private String model;
        private String serialNo;
        private String cpuModel;
        private Integer memoryGb;
        private String diskSpec;
        private String ipAddress;
        private String ownerName;
        private String status;

        public static RackSlot free(Integer u) {
            RackSlot slot = new RackSlot();
            slot.setTopU(u);
            slot.setStartU(u);
            slot.setSizeU(1);
            slot.setState("FREE");
            return slot;
        }

        public static RackSlot of(ServerAsset server) {
            RackSlot slot = new RackSlot();
            slot.setServerId(server.getId());
            slot.setStartU(server.getStartU());
            int size = server.getSizeU() == null || server.getSizeU() < 1 ? 1 : server.getSizeU();
            slot.setSizeU(size);
            slot.setTopU(server.getStartU() + size - 1);
            slot.setState(stateOf(server.getStatus()));
            slot.setAssetNo(server.getAssetNo());
            slot.setBrand(server.getBrand());
            slot.setModel(server.getModel());
            slot.setSerialNo(server.getSerialNo());
            slot.setCpuModel(server.getCpuModel());
            slot.setMemoryGb(server.getMemoryGb());
            slot.setDiskSpec(server.getDiskSpec());
            slot.setIpAddress(server.getIpAddress());
            slot.setOwnerName(server.getOwnerName());
            slot.setStatus(server.getStatus());
            return slot;
        }

        private static String stateOf(String status) {
            if ("REPAIR".equals(status)) {
                return "REPAIR";
            }
            if ("SCRAP".equals(status)) {
                return "SCRAP";
            }
            return "OCCUPIED";
        }
    }

    /** 一个机柜的机架图 */
    @Data
    public static class RackView {
        private Long cabinetId;
        private String cabinetCode;
        private String cabinetName;
        private Long roomId;
        private String roomName;
        private Integer totalU;
        private BigDecimal ratedPower;
        private Integer usedU;
        private Integer freeU;
        private List<RackSlot> slots;
    }

    /** 机柜 + 机房名（列表 / 下拉框用） */
    @Data
    public static class CabinetView {
        private Long id;
        private String code;
        private String name;
        private Long roomId;
        private String roomName;
        private Integer rowNo;
        private Integer colNo;
        private Integer totalU;
        private BigDecimal ratedPower;
        private Integer usedU;
        private String remark;

        public static CabinetView of(Cabinet cabinet, String roomName, Integer usedU) {
            CabinetView view = new CabinetView();
            view.setId(cabinet.getId());
            view.setCode(cabinet.getCode());
            view.setName(cabinet.getName());
            view.setRoomId(cabinet.getRoomId());
            view.setRoomName(roomName);
            view.setRowNo(cabinet.getRowNo());
            view.setColNo(cabinet.getColNo());
            view.setTotalU(cabinet.getTotalU());
            view.setRatedPower(cabinet.getRatedPower());
            view.setUsedU(usedU);
            view.setRemark(cabinet.getRemark());
            return view;
        }
    }

    /** 顶部汇总卡：能耗页用 */
    @Data
    public static class EnergySummary {
        private String month;
        private BigDecimal totalUsage;
        private BigDecimal totalFee;
        private Integer recordCount;
        private Integer cabinetCount;
        private List<CabinetUsageRow> ranking;
    }

    /** 按机柜分组的用电排行 */
    @Data
    public static class CabinetUsageRow {
        private Long cabinetId;
        private String cabinetCode;
        private BigDecimal usageKwh;
        private BigDecimal fee;
    }

    /** 巡检页顶部汇总 */
    @Data
    public static class InspectionSummary {
        private Long total;
        private Long abnormal;
        private Long unhandled;
    }

    /** 分页结果：比直接序列化 Spring Data 的 Page 干净，前端只看 content / total */
    @Data
    public static class PageResult<T> {
        private List<T> content;
        private long total;
        private int page;
        private int size;

        public static <T> PageResult<T> of(List<T> content, long total, int page, int size) {
            PageResult<T> result = new PageResult<>();
            result.setContent(content);
            result.setTotal(total);
            result.setPage(page);
            result.setSize(size);
            return result;
        }
    }

    /** 隔离台账行：主记录 + 机柜信息 + 影响范围计数 */
    @Data
    public static class IsolationView {
        private Long id;
        private String isolationNo;
        private Long cabinetId;
        private String cabinetCode;
        private String cabinetName;
        private String roomName;
        private LocalDateTime planStart;
        private LocalDateTime planEnd;
        private String reason;
        private String initiator;
        private String manager;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime activatedAt;
        private LocalDateTime releasedAt;
        /** 快照固定的受影响服务器台数 */
        private Integer serverCount;
        /** 冻结的施工工单张数 */
        private Integer frozenCount;

        public static IsolationView of(Isolation iso, Cabinet cabinet, String roomName,
                                       int serverCount, int frozenCount) {
            IsolationView view = new IsolationView();
            view.setId(iso.getId());
            view.setIsolationNo(iso.getIsolationNo());
            view.setCabinetId(iso.getCabinetId());
            view.setCabinetCode(cabinet == null ? "" : cabinet.getCode());
            view.setCabinetName(cabinet == null ? "" : cabinet.getName());
            view.setRoomName(roomName == null ? "" : roomName);
            view.setPlanStart(iso.getPlanStart());
            view.setPlanEnd(iso.getPlanEnd());
            view.setReason(iso.getReason());
            view.setInitiator(iso.getInitiator());
            view.setManager(iso.getManager());
            view.setStatus(iso.getStatus());
            view.setCreatedAt(iso.getCreatedAt());
            view.setActivatedAt(iso.getActivatedAt());
            view.setReleasedAt(iso.getReleasedAt());
            view.setServerCount(serverCount);
            view.setFrozenCount(frozenCount);
            return view;
        }
    }

    /** 快照服务器 vs 当前资产：差异逐台列出，历史快照不被实时数据覆盖 */
    @Data
    public static class ServerDiff {
        private Long serverId;
        private String assetNo;
        private String brand;
        private String model;
        /** 快照：生效时的 U 位 / 归属 / 状态 */
        private Integer snapStartU;
        private Integer snapSizeU;
        private String snapOwnerName;
        private String snapStatus;
        /** 当前：实时资产状态（已删除则为空） */
        private Long currentCabinetId;
        private Integer currentStartU;
        private Integer currentSizeU;
        private String currentOwnerName;
        private String currentStatus;
        /** SAME 一致 / CHANGED 有变化 / MOVED_OUT 已移出本柜 / GONE 资产已删除 / ADDED 隔离后新进入本柜 */
        private String diffType;
    }

    /** 冻结施工快照 vs 工单当前状态 */
    @Data
    public static class OrderDiff {
        private Long orderId;
        private String orderNo;
        private String orderType;
        private String assetNo;
        private Integer targetU;
        private String applicant;
        /** 冻结时状态（快照） */
        private String statusAtFreeze;
        /** 工单当前状态（实时，已删除则为空） */
        private String currentStatus;
    }

    /** 冲突施工：机柜相关且已进入施工中的工单（实时查询，用于阻止生效） */
    @Data
    public static class ConflictOrder {
        private Long orderId;
        private String orderNo;
        private String orderType;
        private String assetNo;
        private String applicant;
        private String status;
    }

    /** 隔离详情：计划信息 + 快照 + 当前差异 + 冲突施工 + 操作记录 */
    @Data
    public static class IsolationDetail {
        private Isolation isolation;
        private String cabinetCode;
        private String cabinetName;
        private String roomName;
        /** 快照服务器与当前的逐台差异（含隔离后新进入本柜的） */
        private List<ServerDiff> serverDiffs;
        /** 冻结施工快照与工单当前状态 */
        private List<OrderDiff> frozenOrders;
        /** 当前施工中工单（实时） */
        private List<ConflictOrder> conflicts;
        /** 操作留痕 */
        private List<IsolationEvent> events;
    }
}
