package com.idc.center.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.idc.center.domain.ServerAsset;

/**
 * IDC 机房的业务规则参数与状态机（集中放一处，便于调整）。
 */
public final class IdcRule {

    private IdcRule() {
    }

    public static final Map<String, String> SERVER_STATUS = Map.of(
            "ONLINE", "在架",
            "OFFLINE", "下架",
            "REPAIR", "维修",
            "SCRAP", "报废");

    public static final Map<String, String> ORDER_TYPE = Map.of(
            "ON", "上架",
            "OFF", "下架",
            "MOVE", "迁移");

    public static final Map<String, String> ORDER_STATUS = Map.of(
            "PENDING", "待审批",
            "APPROVED", "已批复",
            "WORKING", "施工中",
            "DONE", "已完成",
            "REJECTED", "已驳回");

    public static final Map<String, String> ISOLATION_STATUS = Map.of(
            "PENDING", "待生效",
            "ACTIVE", "隔离中",
            "RELEASED", "已解除",
            "CANCELED", "已取消");

    public static final Map<String, String> POWER_STATUS = Map.of(
            "NORMAL", "正常",
            "ABNORMAL", "异常");

    /** 温湿度阈值：温度 ≥ 27℃ 或湿度不在 40%~60% 判为异常 */
    public static final BigDecimal TEMP_LIMIT = BigDecimal.valueOf(27);
    public static final BigDecimal HUMIDITY_MIN = BigDecimal.valueOf(40);
    public static final BigDecimal HUMIDITY_MAX = BigDecimal.valueOf(60);

    /** 默认电价（元/kWh） */
    public static final BigDecimal DEFAULT_PRICE = BigDecimal.valueOf(0.68);

    /** 机柜 U 位数上下限 */
    public static final int MIN_TOTAL_U = 10;
    public static final int MAX_TOTAL_U = 48;

    public static boolean isValidServerStatus(String status) {
        return status != null && SERVER_STATUS.containsKey(status);
    }

    public static String serverStatusName(String status) {
        return status == null ? "" : SERVER_STATUS.getOrDefault(status, status);
    }

    /** 在架 / 维修 / 报废三种状态会在机架图上占住 U 位；下架的不占位 */
    public static boolean occupiesU(String status) {
        return "ONLINE".equals(status) || "REPAIR".equals(status) || "SCRAP".equals(status);
    }

    public static boolean isValidOrderType(String type) {
        return type != null && ORDER_TYPE.containsKey(type);
    }

    public static String orderTypeName(String type) {
        return type == null ? "" : ORDER_TYPE.getOrDefault(type, type);
    }

    public static boolean isValidOrderStatus(String status) {
        return status != null && ORDER_STATUS.containsKey(status);
    }

    public static String orderStatusName(String status) {
        return status == null ? "" : ORDER_STATUS.getOrDefault(status, status);
    }

    /** 状态机下一步：待审批 → 已批复 → 施工中 → 已完成；终态返回 null */
    public static String nextOrderStatus(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "APPROVED";
            case "APPROVED" -> "WORKING";
            case "WORKING" -> "DONE";
            default -> null;
        };
    }

    /** 只有待审批、已批复可以被驳回 */
    public static boolean canReject(String status) {
        return "PENDING".equals(status) || "APPROVED".equals(status);
    }

    public static boolean isValidIsolationStatus(String status) {
        return status != null && ISOLATION_STATUS.containsKey(status);
    }

    public static String isolationStatusName(String status) {
        return status == null ? "" : ISOLATION_STATUS.getOrDefault(status, status);
    }

    /** 隔离快照只固定「在架 / 维修中」的服务器（下架、报废不进影响清单） */
    public static boolean inIsolationScope(String status) {
        return "ONLINE".equals(status) || "REPAIR".equals(status);
    }

    public static boolean isPowerAbnormal(String powerStatus) {
        return "ABNORMAL".equals(powerStatus);
    }

    /** 按温湿度与电源状态判定本次巡检是否异常 */
    public static boolean isAbnormal(BigDecimal temperature, BigDecimal humidity, String powerStatus) {
        if (isPowerAbnormal(powerStatus)) {
            return true;
        }
        if (temperature != null && temperature.compareTo(TEMP_LIMIT) >= 0) {
            return true;
        }
        if (humidity != null && (humidity.compareTo(HUMIDITY_MIN) < 0 || humidity.compareTo(HUMIDITY_MAX) > 0)) {
            return true;
        }
        return false;
    }

    /**
     * U 位冲突校验：与同机柜已占位服务器重叠、或超出机柜总 U 数都算冲突。
     * 返回 null 表示可以放，否则返回给前端的中文提示。
     */
    public static String conflictMessage(List<ServerAsset> inCabinet, int totalU,
                                         Integer startU, Integer sizeU, Long excludeServerId) {
        if (startU == null || sizeU == null) {
            return null;
        }
        if (startU < 1 || sizeU < 1) {
            return "起始 U 位与占用 U 数必须大于 0";
        }
        if (startU + sizeU - 1 > totalU) {
            return "U 位超出机柜范围：该机柜共 " + totalU + "U";
        }
        for (ServerAsset other : inCabinet) {
            if (other.getId() != null && other.getId().equals(excludeServerId)) {
                continue;
            }
            if (!occupiesU(other.getStatus()) || other.getStartU() == null) {
                continue;
            }
            int otherSize = other.getSizeU() == null ? 1 : other.getSizeU();
            int otherEnd = other.getStartU() + otherSize - 1;
            boolean overlap = startU <= otherEnd && other.getStartU() <= startU + sizeU - 1;
            if (overlap) {
                return "U 位冲突：" + startU + "U 起 " + sizeU + "U 与 " + other.getAssetNo()
                        + "（" + other.getStartU() + "U 起 " + otherSize + "U）重叠";
            }
        }
        return null;
    }
}
