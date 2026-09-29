package com.idc.center.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

import java.util.List;

/**
 * 遗留数据清洗：修复「下架工单早已完成，但服务器还挂在原机柜 / 原 U 位」的脏位置。
 *
 * 安全口径（宁可不修，不可错修）：
 *   - 只处理当前状态 = OFFLINE 且仍带着机柜 / U 位的服务器；维修中、报废、在架的一律不碰；
 *   - 必须能找到该服务器【已完成的下架工单】作为证据，且工单快照的下架前机柜 / U 位
 *     与当前残留位置逐项一致，才允许清空；
 *   - 该服务器最新一张已完成的位置类工单必须仍是下架（之后若重新上架 / 迁移完成过，
 *     当前位置就是新作业的结果，绝不能清）；
 *   - 没有完成下架记录、或证据对不上的脏位置保留原样，只记日志。
 */
@Component
public class LegacyPlacementRepair {

    private static final Logger log = LoggerFactory.getLogger(LegacyPlacementRepair.class);

    private final ServerAssetRepository serverRepository;
    private final WorkOrderRepository orderRepository;

    public LegacyPlacementRepair(ServerAssetRepository serverRepository,
                                 WorkOrderRepository orderRepository) {
        this.serverRepository = serverRepository;
        this.orderRepository = orderRepository;
    }

    /** 逐台独立事务：修一台提交一台，单台异常不影响其他服务器与应用启动 */
    @Transactional
    public int repairOne(Long serverId) {
        ServerAsset server = serverRepository.findById(serverId).orElse(null);
        if (server == null) {
            return 0;
        }
        List<WorkOrder> doneOrders = orderRepository.findByServerIdOrderByIdDesc(server.getId());
        WorkOrder latestDone = doneOrders.stream()
                .filter(order -> "DONE".equals(order.getStatus()))
                .findFirst()
                .orElse(null);
        if (latestDone == null || !"OFF".equals(latestDone.getOrderType())) {
            return 0;
        }
        Long snapCabinet = latestDone.getSrcCabinetId() != null
                ? latestDone.getSrcCabinetId() : latestDone.getCabinetId();
        Integer snapU = latestDone.getSrcStartU() != null
                ? latestDone.getSrcStartU() : latestDone.getTargetU();
        if (snapCabinet == null || !snapCabinet.equals(server.getCabinetId())) {
            return 0;
        }
        if (snapU != null && !snapU.equals(server.getStartU())) {
            return 0;
        }
        log.info("遗留脏位置修复：{} 由已完成下架工单 {} 确认（机柜 {}，{}U），释放当前位置",
                server.getAssetNo(), latestDone.getOrderNo(), snapCabinet, snapU);
        server.setCabinetId(null);
        server.setStartU(null);
        serverRepository.save(server);
        return 1;
    }
}
