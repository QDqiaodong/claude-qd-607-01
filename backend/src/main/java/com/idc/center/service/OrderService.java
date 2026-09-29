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
 *
 * 查询、创建、驳回在这里；推进 / 完成落地放在 {@link OrderTxService}，
 * 由行锁保证「工单状态 + 资产状态 + U 位释放」同一事务、并发安全。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter NO_SEQ = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final WorkOrderRepository orderRepository;
    private final ServerAssetRepository serverRepository;
    private final CabinetRepository cabinetRepository;
    private final OrderTxService orderTxService;

    public OrderService(WorkOrderRepository orderRepository,
                        ServerAssetRepository serverRepository,
                        CabinetRepository cabinetRepository,
                        OrderTxService orderTxService) {
        this.orderRepository = orderRepository;
        this.serverRepository = serverRepository;
        this.cabinetRepository = cabinetRepository;
        this.orderTxService = orderTxService;
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

    @Transactional
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

        if ("OFF".equals(req.getOrderType())) {
            // 下架前位置快照：创建即固定，之后资产当前位置被清空 / 重新上架都不改写这两个值
            if (server.getCabinetId() == null || server.getStartU() == null) {
                throw new BizException("该服务器当前没有机柜位置，不能发起下架工单");
            }
            order.setCabinetId(server.getCabinetId());
            order.setTargetU(server.getStartU());
            order.setSrcCabinetId(server.getCabinetId());
            order.setSrcStartU(server.getStartU());
        } else {
            if ("MOVE".equals(req.getOrderType())) {
                // 迁移前原位置同样固定进快照，用于历史追查与完成时校验
                if (server.getCabinetId() == null || server.getStartU() == null) {
                    throw new BizException("该服务器当前没有机柜位置，不能发起迁移工单");
                }
                order.setSrcCabinetId(server.getCabinetId());
                order.setSrcStartU(server.getStartU());
            }
            checkTarget(order, cabinet, server.getId(), server.getSizeU());
        }
        return orderRepository.save(order);
    }

    /** 推进：待审批 → 已批复 → 施工中 → 已完成；到已完成时在同一事务里改服务器位置与状态 */
    public WorkOrder advance(Long id) {
        return orderTxService.advance(id);
    }

    @Transactional
    public WorkOrder reject(Long id, Dtos.RejectReq req) {
        WorkOrder order = orderRepository.findById(id).orElseThrow(() -> new BizException("工单不存在"));
        if (!IdcRule.canReject(order.getStatus())) {
            throw new BizException("当前状态（" + IdcRule.orderStatusName(order.getStatus()) + "）不能驳回");
        }
        order.setStatus("REJECTED");
        order.setRejectReason(req.getReason().trim());
        return orderRepository.save(order);
    }

    /** 新建时预检目标 U 位是否放得下 */
    private void checkTarget(WorkOrder order, Cabinet cabinet, Long serverId, Integer serverSizeU) {
        if (order.getTargetU() == null) {
            throw new BizException("请填写目标起始 U 位");
        }
        int sizeU = serverSizeU == null ? 1 : serverSizeU;
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
