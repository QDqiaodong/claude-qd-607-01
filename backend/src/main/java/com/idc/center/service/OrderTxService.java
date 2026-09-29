package com.idc.center.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.IsolationRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块三：工单状态机的事务核心（推进 / 驳回）。
 *
 * 推进到「已完成」时，工单状态翻转、服务器状态变更、U 位占用 / 释放在同一个事务里完成：
 * 任何一步失败整体回滚，不会留下「工单已完成但机柜还被占着」或「服务器只改了一半」的结果。
 *
 * 并发口径：先锁工单行（两个人推同一张工单只有一个能推进），落地前再按固定顺序
 * （涉及机柜按 id 升序 → 服务器行）加锁，保证下架释放与上架 / 迁移占位严格串行 ——
 * 另一张工单只能在释放完整提交后读到空闲 U 位，不会出现两个服务器落在同一 U。
 * 建单、手工改位置沿用同一锁序，因此任意两个事务之间都构不成加锁环（无死锁）。
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
     * 已是终态（已完成 / 已驳回）再点：已完成幂等返回原单，驳回态明确报错，
     * 因此重复点击、请求重试、服务重启后再次操作都不会重复释放或倒退工单。
     */
    @Transactional
    public WorkOrder advance(Long id) {
        WorkOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("工单不存在"));
        if ("DONE".equals(order.getStatus())) {
            return order;
        }
        String next = IdcRule.nextOrderStatus(order.getStatus());
        if (next == null) {
            throw new BizException("工单已处于终态（" + IdcRule.orderStatusName(order.getStatus()) + "），不能再推进");
        }
        checkIsolation(order);
        if ("APPROVED".equals(next) && order.getApprover() == null) {
            order.setApprover("值班主管");
        }
        if ("WORKING".equals(next) && order.getHandler() == null) {
            order.setHandler("运维组");
        }

        if ("DONE".equals(next)) {
            applyToServer(order);
            order.setFinishTime(LocalDateTime.now());
        }
        order.setStatus(next);
        return orderRepository.saveAndFlush(order);
    }

    /** 驳回也走行锁 + 事务：与并发推进互斥，不会把施工中的工单驳回 */
    @Transactional
    public WorkOrder reject(Long id, Dtos.RejectReq req) {
        WorkOrder order = orderRepository.lockById(id)
                .orElseThrow(() -> new BizException("工单不存在"));
        if (!IdcRule.canReject(order.getStatus())) {
            throw new BizException("当前状态（" + IdcRule.orderStatusName(order.getStatus()) + "）不能驳回");
        }
        order.setStatus("REJECTED");
        order.setRejectReason(req.getReason().trim());
        return orderRepository.save(order);
    }

    /**
     * 完成落地：状态、位置与工单翻转同一事务。
     * 统一锁序「涉及机柜按 id 升序 → 服务器行」：下架先锁原柜再锁服务器释放，
     * 上架 / 迁移先锁源柜、目标柜（升序）再锁服务器占位。
     */
    private void applyToServer(WorkOrder order) {
        if ("OFF".equals(order.getOrderType())) {
            applyOff(order);
        } else {
            applyPlacement(order);
        }
    }

    /** 下架完成：锁原柜 → 锁服务器 → 固定历史位置快照 → 置下架并清空机柜 / U 位（释放 U 位） */
    private void applyOff(WorkOrder order) {
        ServerAsset lookup = serverRepository.findById(order.getServerId())
                .orElseThrow(() -> new BizException("工单关联的服务器不存在"));
        if (lookup.getCabinetId() != null) {
            lockCabinet(lookup.getCabinetId());
        }
        ServerAsset server = serverRepository.lockById(order.getServerId())
                .orElseThrow(() -> new BizException("工单关联的服务器不存在"));

        // 快照取服务器此刻的真实位置（操作前事实），只在尚未留痕时写一次，永久不随后续变化改写
        if (order.getFromCabinetId() == null) {
            order.setFromCabinetId(server.getCabinetId());
            order.setFromStartU(server.getStartU());
            order.setFromSizeU(server.getSizeU());
        }

        if (!IdcRule.occupiesU(server.getStatus())) {
            // 已不在架（被其他工单下架 / 手工改过状态）：没有位置可释放，不能把工单做成完成态
            throw new BizException("服务器当前为「" + IdcRule.serverStatusName(server.getStatus())
                    + "」，已不在架，无法完成下架，请核对工单与资产状态后重新处理");
        }

        server.setStatus("OFFLINE");
        server.setCabinetId(null);
        server.setStartU(null);
        serverRepository.save(server);
    }

    /** 上架 / 迁移完成：机柜 id 升序加锁 → 锁服务器 → 复检 U 位冲突 → 落位并置在架 */
    private void applyPlacement(WorkOrder order) {
        ServerAsset lookup = serverRepository.findById(order.getServerId())
                .orElseThrow(() -> new BizException("工单关联的服务器不存在"));

        // 迁移涉及源柜与目标柜，按 id 升序加锁；上架只有目标柜。锁序全局一致（机柜升序 → 服务器），杜绝死锁
        Long sourceCabinetId = "MOVE".equals(order.getOrderType()) ? lookup.getCabinetId() : null;
        if (sourceCabinetId != null && !sourceCabinetId.equals(order.getCabinetId())) {
            lockCabinetsOrdered(sourceCabinetId, order.getCabinetId());
        } else {
            lockCabinet(order.getCabinetId());
        }

        ServerAsset server = serverRepository.lockById(order.getServerId())
                .orElseThrow(() -> new BizException("工单关联的服务器不存在"));
        Cabinet target = cabinetRepository.findById(order.getCabinetId())
                .orElseThrow(() -> new BizException("目标机柜不存在"));

        int totalU = target.getTotalU() == null ? 42 : target.getTotalU();
        String message = IdcRule.conflictMessage(
                serverRepository.findByCabinetIdOrderByStartUAsc(order.getCabinetId()),
                totalU, order.getTargetU(), server.getSizeU(), server.getId());
        if (message != null) {
            // 典型场景：另一张工单刚占了同一 U 位。整体回滚，工单仍是「施工中」，可改 U 位后再次处理
            throw new BizException(message + "，工单未完成，请调整目标位置后重试");
        }

        if ("MOVE".equals(order.getOrderType()) && order.getFromCabinetId() == null) {
            // 迁移前位置留快照（只写一次），随后资产落到新柜；旧柜 U 位随 cabinetId 改变自然释放
            order.setFromCabinetId(server.getCabinetId());
            order.setFromStartU(server.getStartU());
            order.setFromSizeU(server.getSizeU());
        }

        server.setCabinetId(order.getCabinetId());
        server.setStartU(order.getTargetU());
        server.setStatus("ONLINE");
        serverRepository.save(server);
    }

    private Cabinet lockCabinet(Long cabinetId) {
        return cabinetRepository.lockById(cabinetId)
                .orElseThrow(() -> new BizException("目标机柜不存在"));
    }

    private void lockCabinetsOrdered(Long first, Long second) {
        if (first < second) {
            lockCabinet(first);
            lockCabinet(second);
        } else {
            lockCabinet(second);
            lockCabinet(first);
        }
    }

    /**
     * 机柜停电检修隔离：上架 / 迁移工单在目标机柜隔离期间不得推进（后端强制，前端禁用只是提示）。
     * 隔离解除后这里自然放行。
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
                    + active.getReason() + "），隔离解除前该工单不能推进");
        }
    }
}
