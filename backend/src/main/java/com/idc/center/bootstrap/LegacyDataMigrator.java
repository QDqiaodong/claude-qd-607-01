package com.idc.center.bootstrap;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 遗留数据兼容（应用启动后、接流量前执行一次）：
 *
 * 1. 老库没有工单历史位置快照列时补列，并把已完成下架工单的原机柜 / 起始 U 回填进快照，
 *    保证「已经完成的下架工单能够查到原位置」，且快照不随资产当前位置变化被改写。
 * 2. 识别并安全纠正下架工单已完成、资产却仍占着位置的遗留脏数据 ——
 *    只处理能被「最后一张已完成下架工单」明确确认、且其后没有任何已完成上架 / 迁移工单的服务器：
 *      a) 状态为 OFFLINE、仍停在该工单原位置上的：清空机柜与起始 U 位（释放 U 位）；
 *      b) 状态为 ONLINE、仍停在该工单原位置上的：置为下架并清空机柜与起始 U 位（补做释放）；
 *      c) 当前是维修中 / 待拆解（REPAIR / SCRAP）的：属于明确占位，绝不动；
 *      d) 当前位置与工单原位置对不上、或下架后又有上架 / 迁移完成记录的：情况不明，
 *         不猜不批量清，跳过并打 warn 日志交人工核对。
 *
 * 纠正只发幂等 UPDATE，可重复执行；每纠正一条都打审计日志。
 */
@Component
@Order(0)
public class LegacyDataMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyDataMigrator.class);

    private final JdbcTemplate jdbc;

    public LegacyDataMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean added = ensureSnapshotColumns();
            if (added) {
                backfillOffSnapshots();
            }
            repairDirtyPositions();
        } catch (Exception e) {
            // 迁移失败不能拖垮启动：线上业务仍可运行，脏数据保留原状，由日志暴露后人工处理
            log.error("[legacy-migrate] 遗留数据迁移失败，本次跳过，不影响启动：{}", e.getMessage(), e);
        }
    }

    /** 补三列历史位置快照；返回 true 表示本次新建了列（需要回填） */
    private boolean ensureSnapshotColumns() {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = database() and table_name = 'idc_work_order' "
                        + "and column_name = 'from_cabinet_id'",
                Integer.class);
        if (count != null && count > 0) {
            return false;
        }
        log.info("[legacy-migrate] idc_work_order 缺少历史位置快照列，执行补列");
        jdbc.execute("alter table idc_work_order add column from_cabinet_id BIGINT NULL "
                + "COMMENT '操作前机柜快照（下架/迁移完成时固定）' after target_u");
        jdbc.execute("alter table idc_work_order add column from_start_u INT NULL "
                + "COMMENT '操作前起始 U 位快照' after from_cabinet_id");
        jdbc.execute("alter table idc_work_order add column from_size_u INT NULL "
                + "COMMENT '操作前占用 U 数快照' after from_start_u");
        return true;
    }

    /** 已完成下架工单：用工单自身记录的原机柜 / 目标 U 位（当时即下架前位置）回填快照 */
    private void backfillOffSnapshots() {
        int rows = jdbc.update(
                "update idc_work_order wo left join idc_server s on s.id = wo.server_id "
                        + "set wo.from_cabinet_id = wo.cabinet_id, "
                        + "wo.from_start_u = wo.target_u, "
                        + "wo.from_size_u = s.size_u "
                        + "where wo.order_type = 'OFF' and wo.status = 'DONE' and wo.from_cabinet_id is null");
        log.info("[legacy-migrate] 已完成下架工单历史位置快照回填 {} 张", rows);
    }

    /**
     * 脏位置纠正。候选：有已完成下架工单、当前仍带机柜位置的服务器。
     * 逐台核对「最后一张已完成下架工单」与其后的上架 / 迁移完成记录，再决定清空或跳过。
     */
    private void repairDirtyPositions() {
        List<Long> serverIds = jdbc.queryForList(
                "select distinct wo.server_id from idc_work_order wo "
                        + "join idc_server s on s.id = wo.server_id "
                        + "where wo.order_type = 'OFF' and wo.status = 'DONE' and s.cabinet_id is not null",
                Long.class);
        int repaired = 0;
        int skipped = 0;
        for (Long serverId : serverIds) {
            Map<String, Object> server = jdbc.queryForMap(
                    "select asset_no, status, cabinet_id, start_u from idc_server where id = ?", serverId);
            String status = (String) server.get("status");
            long currentCabinet = ((Number) server.get("cabinet_id")).longValue();
            Integer startU = (Integer) server.get("start_u");

            List<Map<String, Object>> offOrders = jdbc.queryForList(
                    "select id, coalesce(from_cabinet_id, cabinet_id) as off_cabinet, "
                            + "coalesce(from_start_u, target_u) as off_start_u, finish_time "
                            + "from idc_work_order where server_id = ? and order_type = 'OFF' and status = 'DONE' "
                            + "order by id desc",
                    serverId);
            Map<String, Object> lastOff = offOrders.get(0);
            long orderId = ((Number) lastOff.get("id")).longValue();
            long offCabinet = ((Number) lastOff.get("off_cabinet")).longValue();
            int offStartU = ((Number) lastOff.get("off_start_u")).intValue();

            // 下架之后又有已完成的上架 / 迁移：位置是新工单给的，绝不能按旧下架单清空（属正常状态，静默跳过）
            Integer later = jdbc.queryForObject(
                    "select count(*) from idc_work_order where server_id = ? and status = 'DONE' "
                            + "and order_type in ('ON', 'MOVE') and id > ?",
                    Integer.class, serverId, orderId);
            if (later != null && later > 0) {
                skipped++;
                continue;
            }

            // 维修中 / 待拆解按业务规则明确占 U 位，即便有下架工单也保留（正常业务状态，不刷警告）
            if ("REPAIR".equals(status) || "SCRAP".equals(status)) {
                skipped++;
                continue;
            }

            boolean samePlace = currentCabinet == offCabinet
                    && startU != null && startU == offStartU;
            if (!samePlace) {
                // 位置对不上说明下架后又被动过，没有历史工单能证明它该去哪 —— 存疑，必须留痕人工核对
                skipped++;
                log.warn("[legacy-migrate] 服务器 {} 当前位置（柜 {} / {}U）与下架工单 {} 的原位置（柜 {} / {}U）"
                                + "不一致，不做猜测性清空，保留现状待人工核对",
                        serverId, currentCabinet, startU, orderId, offCabinet, offStartU);
                continue;
            }

            // 能由历史工单明确确认：OFFLINE 直接释放；ONLINE 补做「下架 + 释放」
            int updated;
            if ("OFFLINE".equals(status)) {
                updated = jdbc.update(
                        "update idc_server set cabinet_id = null, start_u = null "
                                + "where id = ? and status = 'OFFLINE'", serverId);
            } else if ("ONLINE".equals(status)) {
                updated = jdbc.update(
                        "update idc_server set status = 'OFFLINE', cabinet_id = null, start_u = null "
                                + "where id = ? and status = 'ONLINE'", serverId);
            } else {
                skipped++;
                continue;
            }
            if (updated > 0) {
                repaired++;
                log.warn("[legacy-migrate] 已确认遗留脏位置并纠正：服务器 {} 由下架工单 {} 确认，"
                                + "置为下架并清空原位置（柜 {} / {}U），机柜已用/空闲 U 数随之释放",
                        server.get("asset_no"), orderId, offCabinet, offStartU);
            }
        }
        if (repaired > 0 || skipped > 0) {
            log.info("[legacy-migrate] 遗留脏位置处理完成：安全纠正 {} 台，存疑跳过 {} 台", repaired, skipped);
        }
    }
}
