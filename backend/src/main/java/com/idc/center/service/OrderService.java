package com.idc.center.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.IsolationRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块三：上架 / 下架 / 迁移工单，状态机 待审批 → 已批复 → 施工中 → 已完成。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter NO_SEQ = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final WorkOrderRepository orderRepository;
    private final ServerAssetRepository serverRepository;
    private final CabinetRepository cabinetRepository;
    private final IsolationRepository isolationRepository;

    public OrderService(WorkOrderRepository orderRepository,
                        ServerAssetRepository serverRepository,
                        CabinetRepository cabinetRepository,
                        IsolationRepository isolationRepository) {
        this.orderRepository = orderRepository;
        this.serverRepository = serverRepository;
        this.cabinetRepository = cabinetRepository;
        this.isolationRepository = isolationRepository;
    }

    /**
     * 查询：单号、申请人 contains 忽略大小写，类型与状态精确匹配 —— 全部走 Example，
     * 没有范围条件，所以直接吃 Spring Data 的 findAll(Example, Pageable) 分页。
     */
    public Views.PageResult<WorkOrder> search(Dtos.OrderQuery query) {
        WorkOrder probe = new WorkOrder();
        probe.setOrderNo(ExamplePage.blankToNull(query.getOrderNo()));
        probe.setApplicant(ExamplePage.blankToNull(query.getApplicant()));
        probe.setOrderType(ExamplePage.blankToNull(query.getOrderType()));
        probe.setStatus(ExamplePage.blankToNull(query.getStatus()));
        probe.setCabinetId(query.getCabinetId());

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING)
                .withMatcher("orderType", ExampleMatcher.GenericPropertyMatchers.exact())
                .withMatcher("status", ExampleMatcher.GenericPropertyMatchers.exact());

        Sort sort = Sort.by(Sort.Direction.DESC, "id");
        int size = query.getSize() < 1 ? 10 : query.getSize();
        return ExamplePage.page(orderRepository.findAll(Example.of(probe, matcher),
                PageRequest.of(Math.max(query.getPage(), 0), size, sort)));
    }

    public WorkOrder create(Dtos.OrderCreateReq req) {
        ServerAsset server = serverRepository.findById(req.getServerId())
                .orElseThrow(() -> new BizException("服务器不存在"));
        if (!IdcRule.isValidOrderType(req.getOrderType())) {
            throw new BizException("工单类型不合法：" + req.getOrderType());
        }
        if ("ON".equals(req.getOrderType()) && IdcRule.occupiesU(server.getStatus())) {
            throw new BizException("该服务器当前已在架，不能再发起上架工单");
        }
        if ("OFF".equals(req.getOrderType()) && !IdcRule.occupiesU(server.getStatus())) {
            throw new BizException("该服务器不在架，无需下架");
        }
        Long cabinetId = req.getCabinetId() != null ? req.getCabinetId() : server.getCabinetId();
        if (cabinetId == null) {
            throw new BizException("请选择目标机柜");
        }
        Cabinet cabinet = cabinetRepository.findById(cabinetId).orElseThrow(() -> new BizException("机柜不存在"));

        WorkOrder order = new WorkOrder();
        order.setOrderNo("WO" + NO_SEQ.format(LocalDateTime.now()));
        order.setOrderType(req.getOrderType());
        order.setServerId(server.getId());
        order.setAssetNo(server.getAssetNo());
        order.setCabinetId(cabinetId);
        order.setTargetU(req.getTargetU() == null ? server.getStartU() : req.getTargetU());
        order.setApplicant(req.getApplicant().trim());
        order.setApplyTime(LocalDateTime.now());
        order.setStatus("PENDING");
        order.setRemark(req.getRemark());

        if (!"OFF".equals(req.getOrderType())) {
            checkTarget(order, cabinet, server.getId());
        }
        return orderRepository.save(order);
    }

    /** 推进：待审批 → 已批复 → 施工中 → 已完成；到已完成时真正改动服务器的位置与状态 */
    public WorkOrder advance(Long id) {
        WorkOrder order = orderRepository.findById(id).orElseThrow(() -> new BizException("工单不存在"));
        String next = IdcRule.nextOrderStatus(order.getStatus());
        if (next == null) {
            throw new BizException("工单已处于终态（" + IdcRule.orderStatusName(order.getStatus()) + "），不能再推进");
        }
        checkIsolation(order);
        if ("APPROVED".equals(next)) {
            order.setApprover("值班主管");
        }
        if ("WORKING".equals(next)) {
            order.setHandler("运维组");
        }
        order.setStatus(next);
        if ("DONE".equals(next)) {
            applyToServer(order);
            order.setFinishTime(LocalDateTime.now());
        }
        return orderRepository.save(order);
    }

    /**
     * 机柜停电检修隔离：上架 / 迁移工单在目标机柜隔离期间不得推进（后端强制，前端禁用只是提示）。
     * 隔离解除后这里自然放行；U 位冲突等其他业务规则不受影响，仍按原逻辑校验。
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

    public WorkOrder reject(Long id, Dtos.RejectReq req) {
        WorkOrder order = orderRepository.findById(id).orElseThrow(() -> new BizException("工单不存在"));
        if (!IdcRule.canReject(order.getStatus())) {
            throw new BizException("当前状态（" + IdcRule.orderStatusName(order.getStatus()) + "）不能驳回");
        }
        order.setStatus("REJECTED");
        order.setRejectReason(req.getReason().trim());
        return orderRepository.save(order);
    }

    /** 工单完成时落地到服务器资产 */
    private void applyToServer(WorkOrder order) {
        ServerAsset server = serverRepository.findById(order.getServerId())
                .orElseThrow(() -> new BizException("工单关联的服务器不存在"));
        if ("OFF".equals(order.getOrderType())) {
            server.setStatus("OFFLINE");
        } else {
            Cabinet cabinet = cabinetRepository.findById(order.getCabinetId())
                    .orElseThrow(() -> new BizException("目标机柜不存在"));
            int totalU = cabinet.getTotalU() == null ? 42 : cabinet.getTotalU();
            String message = IdcRule.conflictMessage(
                    serverRepository.findByCabinetIdOrderByStartUAsc(order.getCabinetId()),
                    totalU, order.getTargetU(), server.getSizeU(), server.getId());
            if (message != null) {
                throw new BizException(message);
            }
            server.setCabinetId(order.getCabinetId());
            server.setStartU(order.getTargetU());
            server.setStatus("ONLINE");
        }
        serverRepository.save(server);
    }

    /** 新建时预检目标 U 位是否放得下 */
    private void checkTarget(WorkOrder order, Cabinet cabinet, Long serverId) {
        if (order.getTargetU() == null) {
            throw new BizException("请填写目标起始 U 位");
        }
        ServerAsset server = serverRepository.findById(serverId).orElse(null);
        int sizeU = server == null || server.getSizeU() == null ? 1 : server.getSizeU();
        int totalU = cabinet.getTotalU() == null ? 42 : cabinet.getTotalU();
        String message = IdcRule.conflictMessage(
                serverRepository.findByCabinetIdOrderByStartUAsc(order.getCabinetId()),
                totalU, order.getTargetU(), sizeU, serverId);
        if (message != null) {
            throw new BizException(message);
        }
    }

    public List<WorkOrder> byServer(Long serverId) {
        return orderRepository.findByServerIdOrderByIdDesc(serverId);
    }
}
