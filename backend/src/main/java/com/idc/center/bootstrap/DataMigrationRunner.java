package com.idc.center.bootstrap;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.idc.center.domain.ServerAsset;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.service.LegacyPlacementRepair;

import jakarta.annotation.PostConstruct;

/**
 * 启动期数据兼容（幂等，可安全反复执行 / 服务重启后再跑）：
 *   1. 老库补历史位置快照列 src_cabinet_id / src_start_u；
 *   2. 用【已完成下架工单】的 cabinet_id / target_u 回填快照列；
 *   3. 仅对「能由历史下架工单明确确认」的遗留脏位置（OFFLINE 但仍挂原柜原 U）做安全纠正，
 *      维修中 / 待拆解 / 没有完成下架记录的服务器一律不动（判定在 LegacyPlacementRepair）。
 *
 * 新库由 schema.sql 直接建好列、种子数据本就合规，这套逻辑跑过去什么都不改。
 */
@Component
public class DataMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final ServerAssetRepository serverRepository;
    private final LegacyPlacementRepair legacyPlacementRepair;

    public DataMigrationRunner(JdbcTemplate jdbcTemplate,
                               ServerAssetRepository serverRepository,
                               LegacyPlacementRepair legacyPlacementRepair) {
        this.jdbcTemplate = jdbcTemplate;
        this.serverRepository = serverRepository;
        this.legacyPlacementRepair = legacyPlacementRepair;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("src_cabinet_id",
                "ALTER TABLE idc_work_order ADD COLUMN src_cabinet_id BIGINT NULL COMMENT '历史位置快照：作业发起时所在机柜' AFTER cabinet_id");
        addColumnIfMissing("src_start_u",
                "ALTER TABLE idc_work_order ADD COLUMN src_start_u INT NULL COMMENT '历史位置快照：作业发起时起始 U 位' AFTER target_u");

        // 只回填下架工单：它的 cabinet_id / target_u 语义就是下架前位置；迁移老单的原位置无从得知，留空不猜
        int backfilled = jdbcTemplate.update("""
                UPDATE idc_work_order
                   SET src_cabinet_id = cabinet_id, src_start_u = target_u
                 WHERE order_type = 'OFF' AND src_cabinet_id IS NULL
                """);
        if (backfilled > 0) {
            log.info("历史下架工单位置快照回填完成：{} 张", backfilled);
        }

        int repaired = 0;
        List<ServerAsset> candidates = serverRepository.findAll().stream()
                .filter(server -> "OFFLINE".equals(server.getStatus())
                        && server.getCabinetId() != null && server.getStartU() != null)
                .toList();
        for (ServerAsset candidate : candidates) {
            try {
                repaired += legacyPlacementRepair.repairOne(candidate.getId());
            } catch (RuntimeException e) {
                log.warn("遗留脏位置修复跳过 {}：{}", candidate.getAssetNo(), e.getMessage());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("遗留脏位置检查完成：OFFLINE 但仍带位置的服务器 {} 台，其中凭历史下架工单安全纠正 {} 台，其余保留不动",
                    candidates.size(), repaired);
        }
    }

    private void addColumnIfMissing(String column, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'idc_work_order' AND column_name = ?
                """, Integer.class, column);
        if (exists != null && exists == 0) {
            jdbcTemplate.execute(ddl);
            log.info("已为 idc_work_order 补列：{}", column);
        }
    }
}
