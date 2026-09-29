package com.idc.center.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.IsolationRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块三：工单状态机的事务核心（推进 / 完成落地）。
 *
 * 推进到「已完成」时，工单状态、服务器状态、U 位释放 / 占用必须是一个不可拆开的结果：
 * 任何一步失败整体回滚，不会留下「工单已完成但机柜还被占着」或「位置清了一半」的中间态。
 *
 * 并发口径（全部走 SELECT ... FOR UPDATE 行锁）：
 *   1. 先锁工单行 —— 两个人同时推进同一张单，后到的在锁上等待，拿到锁后看到已是终态即报错，
 *      下架释放只会发生一次；重复点击 / 请求重试 / 服务重启后再试同理。
 *   2. 再按机柜 id 升序锁涉及的机柜行（下架锁原柜、上架锁目标柜、迁移锁原柜+目标柜）——
 *      另一张上架 / 迁移工单争用刚释放的 U 位时，必须等下架事务完整提交后才能读到空 U，
 *      从根上避免两台服务器落在同一 U。
 *   3. 最后锁服务器行 —— 同一台机器被两张工单同时改状态 / 位置时串行化。
 *   柜 → 服务器的加锁顺序全应用一致，不会出现锁环死锁。
 */
@Service
public class OrderTxService {

    private final WorkOrderRepository orderRepository;
    private final ServerAssetRepository serverRepository;
    private final CabinetRepository cabinetRepository;
    private final IsolationRepository isolationRepository;

    public OrderTxService(WorkOrderRepository orderRepository,
                          ServerAssetRepository serverRepository,
                          CabinetRepository cabinetRepository,
                          IsolationRepository isolationRepository) {
        this.orderRepository = orderRepository;
        this.serverRepository = serverRepository;
        this.cabinetRepository = cabinetRepository;
        this.isolationRepository = isolationRepository;
    }

    /**
     * 推进：待审批 → 已批复 → 施工中 → 已完成。
     * 终态再推进直接报错（后端权威，前端禁用 / 防重复点击只是体验），报错信息可指导操作人再次处理。
     */
    @Transactional
    public WorkOrder advance(Long id) {
        WorkOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("工单不存在"));
        String next = IdcRule.nextOrderStatus(order.getStatus());
        if (next == null) {
            throw new BizException("工单已处于终态（" + IdcRule.orderStatusName(order.getStatus()) + "），不能再推进");
        }

        // 非完成步：只翻状态，不碰资产；隔离校验在这里做即可
        if (!"DONE".equals(next)) {
            checkIsolation(order);
        }
        if ("APPROVED".equals(next)) {
            order.setApprover("值班主管");
        }
        if ("WORKING".equals(next)) {
            order.setHandler("运维组");
        }
        order.setStatus(next);

        if ("DONE".equals(next)) {
            applyCompletion(order);
            order.setFinishTime(LocalDateTime.now());
        }
        // saveAndFlush：任何落库异常都在本事务内抛出并整体回滚
        return orderRepository.saveAndFlush(order);
    }

    /** 完成落地：锁柜 → 锁机 → 校验 → 改资产，全部与工单状态翻转同生共死 */
    private void applyCompletion(WorkOrder order) {
        List<Long> cabinetIds = new ArrayList<>();
        if ("OFF".equals(order.getOrderType())) {
            Long srcCabinet = order.getSrcCabinetId() != null ? order.getSrcCabinetId() : order.getCabinetId();
            if (srcCabinet == null) {
                throw new BizException("下架工单缺少下架前机柜记录，无法确认要释放的位置，请补全工单后重试");
            }
            cabinetIds.add(srcCabinet);
        } else {
            if (order.getCabinetId() == null) {
                throw new BizException("工单缺少目标机柜，无法完成上架 / 迁移");
            }
            cabinetIds.add(order.getCabinetId());
            if ("MOVE".equals(order.getOrderType()) && order.getSrcCabinetId() != null
                    && !order.getSrcCabinetId().equals(order.getCabinetId())) {
                cabinetIds.add(order.getSrcCabinetId());
            }
        }
        cabinetIds.stream().distinct().sorted(Comparator.naturalOrder()).forEach(this::lockCabinet);

        ServerAsset server = serverRepository.lockById(order.getServerId())
                .orElseThrow(() -> new BizException("工单关联的服务器不存在，位置未做任何变更，请核对后重试"));

        switch (order.getOrderType()) {
            case "OFF" -> applyOffline(order, server);
            case "ON" -> applyOnline(order, server);
            case "MOVE" -> applyMove(order, server);
            default -> throw new BizException("工单类型不合法：" + order.getOrderType());
        }
    }

    /** 下架完成：确认服务器仍在工单记录的原位置后，状态置下架并释放机柜 / U 位（位置只留在工单快照里） */
    private void applyOffline(WorkOrder order, ServerAsset server) {
        Long srcCabinet = order.getSrcCabinetId() != null ? order.getSrcCabinetId() : order.getCabinetId();
        Integer srcU = order.getSrcStartU() != null ? order.getSrcStartU() : order.getTargetU();

        if (server.getCabinetId() == null || server.getStartU() == null) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前已无机柜位置（状态："
                    + IdcRule.serverStatusName(server.getStatus()) + "），下架不能重复执行；"
                    + "如位置显示仍异常，请按资产当前状态人工核对后再处理");
        }
        if (!IdcRule.occupiesU(server.getStatus())) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前状态为「"
                    + IdcRule.serverStatusName(server.getStatus()) + "」且仍挂在机柜上，与下架工单不符，"
                    + "请先核实资产状态再完成工单");
        }
        if (!srcCabinet.equals(server.getCabinetId())) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前位置与下架工单记录的下架前位置不一致"
                    + "（工单记录机柜 " + srcCabinet + "），不能按本工单释放，请核实后重新发起");
        }
        if (srcU != null && !srcU.equals(server.getStartU())) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前起始 U 位（"
                    + server.getStartU() + "U）与下架工单记录（" + srcU + "U）不一致，"
                    + "位置可能已被调整，请核实后重新发起");
        }
        server.setStatus("OFFLINE");
        server.setCabinetId(null);
        server.setStartU(null);
        serverRepository.saveAndFlush(server);
    }

    /** 上架完成：在目标柜锁内做最新冲突校验后占用 U 位（目标柜已在 applyCompletion 中加锁） */
    private void applyOnline(WorkOrder order, ServerAsset server) {
        checkIsolation(order);
        if (server.getCabinetId() != null && IdcRule.occupiesU(server.getStatus())) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前已在机柜占用 U 位"
                    + "（机柜 " + server.getCabinetId() + "，" + server.getStartU() + "U），"
                    + "不能走上架工单，请改走迁移或下架");
        }
        Cabinet cabinet = cabinetRepository.findById(order.getCabinetId())
                .orElseThrow(() -> new BizException("目标机柜不存在或已删除，位置未做任何变更"));
        int totalU = cabinet.getTotalU() == null ? 42 : cabinet.getTotalU();
        String message = IdcRule.conflictMessage(
                serverRepository.findByCabinetIdOrderByStartUAsc(order.getCabinetId()),
                totalU, order.getTargetU(), server.getSizeU(), server.getId());
        if (message != null) {
            throw new BizException(message + "；工单未完成，可调整目标 U 位后重试");
        }
        server.setCabinetId(order.getCabinetId());
        server.setStartU(order.getTargetU());
        server.setStatus("ONLINE");
        serverRepository.saveAndFlush(server);
    }

    /** 迁移完成：原柜位置让出、目标柜在锁内冲突校验后占用，状态保持（在架 / 维修）不变 */
    private void applyMove(WorkOrder order, ServerAsset server) {
        checkIsolation(order);
        if (!IdcRule.occupiesU(server.getStatus()) || server.getCabinetId() == null) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前不在架（无机柜位置），"
                    + "不能执行迁移；如需上架请走上架工单");
        }
        if (order.getSrcCabinetId() != null
                && (!order.getSrcCabinetId().equals(server.getCabinetId())
                        || (order.getSrcStartU() != null && !order.getSrcStartU().equals(server.getStartU())))) {
            throw new BizException("服务器「" + server.getAssetNo() + "」当前位置与迁移工单记录的原位置不一致"
                    + "（工单记录机柜 " + order.getSrcCabinetId() + "，" + order.getSrcStartU()
                    + "U），请核实后重新发起");
        }
        Cabinet target = cabinetRepository.findById(order.getCabinetId())
                .orElseThrow(() -> new BizException("目标机柜不存在或已删除，位置未做任何变更"));
        int totalU = target.getTotalU() == null ? 42 : target.getTotalU();
        String message = IdcRule.conflictMessage(
                serverRepository.findByCabinetIdOrderByStartUAsc(order.getCabinetId()),
                totalU, order.getTargetU(), server.getSizeU(), server.getId());
        if (message != null) {
            throw new BizException(message + "；工单未完成，可调整目标 U 位后重试");
        }
        server.setCabinetId(order.getCabinetId());
        server.setStartU(order.getTargetU());
        serverRepository.saveAndFlush(server);
    }

    private Cabinet lockCabinet(Long cabinetId) {
        return cabinetRepository.lockById(cabinetId)
                .orElseThrow(() -> new BizException("目标机柜不存在或已删除，位置未做任何变更"));
    }

    /**
     * 机柜停电检修隔离：上架 / 迁移工单在目标机柜隔离期间不得完成（后端强制，前端禁用只是提示）。
     * 必须在拿到机柜行锁后调用 —— 这样隔离解除 / 生效与工单完成在同一把柜锁上串行。
     */
    private void checkIsolation(WorkOrder order) {
        if (!"ON".equals(order.getOrderType()) && !"MOVE".equals(order.getOrderType())) {
            return;
        }
        if (order.getCabinetId() == null) {
            return;
        }
        Isolation active = isolationRepository.findByActiveCabinet(order.getCabinetId()).orElse(null);
        if (active != null) {
            throw new BizException("目标机柜正在停电检修隔离中（" + active.getIsolationNo() + "："
                    + active.getReason() + "），隔离解除前该工单不能完成");
        }
    }
}
