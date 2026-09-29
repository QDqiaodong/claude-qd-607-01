package com.idc.center.service;

import java.util.List;
import java.util.function.Predicate;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.ServerAsset;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块二：服务器资产。多条件筛选走 Query by Example。
 *
 * 写操作统一锁序「相关机柜（id 升序）→ 服务器行」，与工单完成落地完全一致，
 * 因此手工改位置 / 改状态与上架 / 迁移 / 下架工单并发时构不成加锁环。
 */
@Service
public class ServerService {

    private final ServerAssetRepository serverRepository;
    private final CabinetRepository cabinetRepository;
    private final WorkOrderRepository orderRepository;

    public ServerService(ServerAssetRepository serverRepository,
                         CabinetRepository cabinetRepository,
                         WorkOrderRepository orderRepository) {
        this.serverRepository = serverRepository;
        this.cabinetRepository = cabinetRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * 查询：字符串字段 contains 忽略大小写、状态与机柜精确匹配（走 ExampleMatcher）；
     * 起始 U 位区间、内存下限这类范围条件在 Example 结果上再过一道。
     */
    public Views.PageResult<ServerAsset> search(Dtos.ServerQuery query) {
        ServerAsset probe = new ServerAsset();
        probe.setAssetNo(ExamplePage.blankToNull(query.getAssetNo()));
        probe.setBrand(ExamplePage.blankToNull(query.getBrand()));
        probe.setModel(ExamplePage.blankToNull(query.getModel()));
        probe.setSerialNo(ExamplePage.blankToNull(query.getSerialNo()));
        probe.setStatus(ExamplePage.blankToNull(query.getStatus()));
        probe.setCabinetId(query.getCabinetId());

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING)
                .withMatcher("status", ExampleMatcher.GenericPropertyMatchers.exact());

        List<ServerAsset> rows = ExamplePage.allDesc(serverRepository, Example.of(probe, matcher), "id");
        return ExamplePage.page(rows, matchRange(query), query.getPage(), query.getSize());
    }

    @Transactional
    public ServerAsset create(Dtos.ServerSaveReq req) {
        if (serverRepository.existsByAssetNo(req.getAssetNo())) {
            throw new BizException("资产编号已存在：" + req.getAssetNo());
        }
        if (!IdcRule.isValidServerStatus(req.getStatus())) {
            throw new BizException("服务器状态不合法：" + req.getStatus());
        }
        ServerAsset server = new ServerAsset();
        apply(server, req);
        normalizeOfflinePosition(server);
        // 新建无既存服务器行，只需锁目标柜后查冲突
        if (IdcRule.occupiesU(server.getStatus())) {
            cabinetRepository.lockById(server.getCabinetId())
                    .orElseThrow(() -> new BizException("机柜不存在"));
            checkPlacementLocked(server, null);
        }
        return serverRepository.save(server);
    }

    @Transactional
    public ServerAsset update(Long id, Dtos.ServerSaveReq req) {
        if (!IdcRule.isValidServerStatus(req.getStatus())) {
            throw new BizException("服务器状态不合法：" + req.getStatus());
        }
        ServerAsset before = serverRepository.findById(id).orElseThrow(() -> new BizException("服务器不存在"));
        // 锁序「原柜 / 目标柜升序 → 服务器」：可能涉及两个柜（改挂到别的柜）
        lockInvolvedCabinets(before.getCabinetId(),
                "OFFLINE".equals(req.getStatus()) ? null : req.getCabinetId());
        ServerAsset server = serverRepository.lockById(id).orElseThrow(() -> new BizException("服务器不存在"));

        ServerAsset other = new ServerAsset();
        other.setAssetNo(req.getAssetNo());
        List<ServerAsset> sameNo = serverRepository.findAll(Example.of(other, ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withMatcher("assetNo", ExampleMatcher.GenericPropertyMatchers.exact())));
        if (sameNo.stream().anyMatch(row -> !row.getId().equals(id))) {
            throw new BizException("资产编号已存在：" + req.getAssetNo());
        }
        apply(server, req);
        normalizeOfflinePosition(server);
        checkPlacementLocked(server, id);
        return serverRepository.save(server);
    }

    /**
     * 状态变更：在架/下架/维修/报废。
     * 改成下架与工单完成同一口径 —— 当前机柜与起始 U 位必须同时清空（释放 U 位），
     * 不会留下「已下架但仍挂在本柜」的脏位置；下架前位置由下架工单的快照永久保留。
     */
    @Transactional
    public ServerAsset changeStatus(Long id, Dtos.ServerStatusReq req) {
        if (!IdcRule.isValidServerStatus(req.getStatus())) {
            throw new BizException("服务器状态不合法：" + req.getStatus());
        }
        ServerAsset before = serverRepository.findById(id).orElseThrow(() -> new BizException("服务器不存在"));
        if (before.getCabinetId() != null) {
            cabinetRepository.lockById(before.getCabinetId());
        }
        ServerAsset server = serverRepository.lockById(id).orElseThrow(() -> new BizException("服务器不存在"));
        if ("SCRAP".equals(server.getStatus()) && !"SCRAP".equals(req.getStatus())) {
            throw new BizException("已报废的服务器不能再变更状态");
        }
        server.setStatus(req.getStatus());
        normalizeOfflinePosition(server);
        checkPlacementLocked(server, id);
        return serverRepository.save(server);
    }

    public ServerAsset get(Long id) {
        return serverRepository.findById(id).orElseThrow(() -> new BizException("服务器不存在"));
    }

    /**
     * 服务器详情：当前资产 + 最近一张已完成下架工单的历史位置快照。
     * 当前位置（cabinetId/startU）与下架前位置（工单快照）分开给页面，互不覆盖：
     * 以后重新上架，这里的下架前位置仍是原机柜、原 U 位。
     */
    @Transactional(readOnly = true)
    public Views.ServerDetail detail(Long id) {
        ServerAsset server = get(id);
        Views.ServerDetail detail = Views.ServerDetail.of(server);
        orderRepository.findByServerIdOrderByIdDesc(id).stream()
                .filter(order -> "OFF".equals(order.getOrderType()) && "DONE".equals(order.getStatus()))
                .findFirst()
                .ifPresent(order -> {
                    // 新数据读完成时固定的快照列；老数据没有快照列时用工单上的原机柜 / U 位兜底
                    detail.setLastOffOrderId(order.getId());
                    detail.setLastOffOrderNo(order.getOrderNo());
                    detail.setOffCabinetId(
                            order.getFromCabinetId() != null ? order.getFromCabinetId() : order.getCabinetId());
                    detail.setOffStartU(
                            order.getFromStartU() != null ? order.getFromStartU() : order.getTargetU());
                    detail.setOffSizeU(order.getFromSizeU() != null ? order.getFromSizeU() : server.getSizeU());
                    detail.setOffFinishTime(order.getFinishTime());
                });
        return detail;
    }

    /** 下拉框：只取在架的（发起下架工单时选它） */
    public List<ServerAsset> options() {
        return serverRepository.findAll().stream()
                .filter(server -> IdcRule.occupiesU(server.getStatus()))
                .sorted((a, b) -> String.valueOf(a.getAssetNo()).compareTo(String.valueOf(b.getAssetNo())))
                .toList();
    }

    private void apply(ServerAsset server, Dtos.ServerSaveReq req) {
        server.setAssetNo(req.getAssetNo().trim());
        server.setBrand(req.getBrand());
        server.setModel(req.getModel());
        server.setSerialNo(req.getSerialNo());
        server.setCpuModel(req.getCpuModel());
        server.setMemoryGb(req.getMemoryGb());
        server.setDiskSpec(req.getDiskSpec());
        server.setIpAddress(req.getIpAddress());
        server.setOsVersion(req.getOsVersion());
        server.setCabinetId(req.getCabinetId());
        server.setStartU(req.getStartU());
        server.setSizeU(req.getSizeU() == null ? 1 : req.getSizeU());
        server.setStatus(req.getStatus());
        server.setOwnerName(req.getOwnerName());
        server.setBuyDate(req.getBuyDate());
        server.setRemark(req.getRemark());
    }

    /** 下架状态的服务器不允许带机柜 / U 位：机柜、起始 U 一并视为未分配 */
    private void normalizeOfflinePosition(ServerAsset server) {
        if ("OFFLINE".equals(server.getStatus())) {
            server.setCabinetId(null);
            server.setStartU(null);
        }
    }

    /** 涉及的原柜 / 目标柜按 id 升序加锁（同一柜只加一次），配合「机柜 → 服务器」全局锁序防死锁 */
    private void lockInvolvedCabinets(Long firstCabinetId, Long secondCabinetId) {
        if (firstCabinetId == null && secondCabinetId == null) {
            return;
        }
        if (firstCabinetId == null) {
            cabinetRepository.lockById(secondCabinetId).orElseThrow(() -> new BizException("机柜不存在"));
            return;
        }
        if (secondCabinetId == null || firstCabinetId.equals(secondCabinetId)) {
            cabinetRepository.lockById(firstCabinetId).orElseThrow(() -> new BizException("机柜不存在"));
            return;
        }
        if (firstCabinetId < secondCabinetId) {
            cabinetRepository.lockById(firstCabinetId).orElseThrow(() -> new BizException("机柜不存在"));
            cabinetRepository.lockById(secondCabinetId).orElseThrow(() -> new BizException("机柜不存在"));
        } else {
            cabinetRepository.lockById(secondCabinetId).orElseThrow(() -> new BizException("机柜不存在"));
            cabinetRepository.lockById(firstCabinetId).orElseThrow(() -> new BizException("机柜不存在"));
        }
    }

    /** 冲突复检：调用方已持有相关机柜行锁；只有占 U 位的状态才校验，下架的不占位允许无机柜 */
    private void checkPlacementLocked(ServerAsset server, Long excludeId) {
        if (!IdcRule.occupiesU(server.getStatus())) {
            return;
        }
        if (server.getCabinetId() == null) {
            throw new BizException("在架/维修/报废的服务器必须指定机柜");
        }
        Cabinet cabinet = cabinetRepository.findById(server.getCabinetId())
                .orElseThrow(() -> new BizException("机柜不存在"));
        int totalU = cabinet.getTotalU() == null ? 42 : cabinet.getTotalU();
        String message = IdcRule.conflictMessage(
                serverRepository.findByCabinetIdOrderByStartUAsc(server.getCabinetId()),
                totalU, server.getStartU(), server.getSizeU(), excludeId);
        if (message != null) {
            throw new BizException(message);
        }
    }
}
