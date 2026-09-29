package com.idc.center.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块三：上架 / 下架 / 迁移工单，状态机 待审批 → 已批复 → 施工中 → 已完成。
 * 这里负责查询、建单校验与组装；推进 / 驳回的事务落地在 {@link OrderTxService}。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter NO_SEQ = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final WorkOrderRepository orderRepository;
    private final ServerAssetRepository serverRepository;
    private final CabinetRepository cabinetRepository;
    private final OrderTxService txService;

    public OrderService(WorkOrderRepository orderRepository,
                        ServerAssetRepository serverRepository,
                        CabinetRepository cabinetRepository,
                        OrderTxService txService) {
        this.orderRepository = orderRepository;
        this.serverRepository = serverRepository;
        this.cabinetRepository = cabinetRepository;
        this.txService = txService;
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

    /** 建单：统一锁序「机柜 → 服务器」，加锁后复检状态与 U 位，避免并发死锁与重位 */
    @Transactional
    public WorkOrder create(Dtos.OrderCreateReq req) {
        if (!IdcRule.isValidOrderType(req.getOrderType())) {
            throw new BizException("工单类型不合法：" + req.getOrderType());
        }
        ServerAsset preview = serverRepository.findById(req.getServerId())
                .orElseThrow(() -> new BizException("服务器不存在"));
        if ("ON".equals(req.getOrderType()) && IdcRule.occupiesU(preview.getStatus())) {
            throw new BizException("该服务器当前已在架，不能再发起上架工单");
        }
        if ("OFF".equals(req.getOrderType()) && !IdcRule.occupiesU(preview.getStatus())) {
            throw new BizException("该服务器不在架，无需下架");
        }
        Long cabinetId = req.getCabinetId() != null ? req.getCabinetId() : preview.getCabinetId();
        if (cabinetId == null) {
            throw new BizException("请选择目标机柜");
        }

        // 先锁机柜、再锁服务器：与工单完成落地、手工改位置同一全局锁序（机柜 → 服务器）
        Cabinet cabinet = cabinetRepository.lockById(cabinetId)
                .orElseThrow(() -> new BizException("机柜不存在"));
        ServerAsset server = serverRepository.lockById(req.getServerId())
                .orElseThrow(() -> new BizException("服务器不存在"));
        // 加锁后复检状态，挡住预检与加锁之间被别的事务改动的情况
        if ("ON".equals(req.getOrderType()) && IdcRule.occupiesU(server.getStatus())) {
            throw new BizException("该服务器当前已在架，不能再发起上架工单");
        }
        if ("OFF".equals(req.getOrderType()) && !IdcRule.occupiesU(server.getStatus())) {
            throw new BizException("该服务器不在架，无需下架");
        }

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
            checkTarget(order, cabinet, server);
        }
        return orderRepository.save(order);
    }

    /** 推进：待审批 → 已批复 → 施工中 → 已完成；到已完成时同事务落地服务器位置与状态 */
    public WorkOrder advance(Long id) {
        return txService.advance(id);
    }

    public WorkOrder reject(Long id, Dtos.RejectReq req) {
        return txService.reject(id, req);
    }

    /** 新建时预检目标 U 位是否放得下（调用方已持有目标机柜锁） */
    private void checkTarget(WorkOrder order, Cabinet cabinet, ServerAsset server) {
        if (order.getTargetU() == null) {
            throw new BizException("请填写目标起始 U 位");
        }
        int sizeU = server.getSizeU() == null ? 1 : server.getSizeU();
        int totalU = cabinet.getTotalU() == null ? 42 : cabinet.getTotalU();
        String message = IdcRule.conflictMessage(
                serverRepository.findByCabinetIdOrderByStartUAsc(order.getCabinetId()),
                totalU, order.getTargetU(), sizeU, server.getId());
        if (message != null) {
            throw new BizException(message);
        }
    }

    public List<WorkOrder> byServer(Long serverId) {
        return orderRepository.findByServerIdOrderByIdDesc(serverId);
    }
}
