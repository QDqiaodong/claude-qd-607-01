package com.idc.center.service;

import java.util.List;
import java.util.function.Predicate;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.stereotype.Service;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.ServerAsset;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.ServerAssetRepository;

/**
 * 模块二：服务器资产。多条件筛选走 Query by Example。
 */
@Service
public class ServerService {

    private final ServerAssetRepository serverRepository;
    private final CabinetRepository cabinetRepository;

    public ServerService(ServerAssetRepository serverRepository, CabinetRepository cabinetRepository) {
        this.serverRepository = serverRepository;
        this.cabinetRepository = cabinetRepository;
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

    private Predicate<ServerAsset> matchRange(Dtos.ServerQuery query) {
        return server -> {
            if (query.getMinStartU() != null
                    && (server.getStartU() == null || server.getStartU() < query.getMinStartU())) {
                return false;
            }
            if (query.getMaxStartU() != null
                    && (server.getStartU() == null || server.getStartU() > query.getMaxStartU())) {
                return false;
            }
            return query.getMinMemory() == null
                    || (server.getMemoryGb() != null && server.getMemoryGb() >= query.getMinMemory());
        };
    }

    public ServerAsset create(Dtos.ServerSaveReq req) {
        if (serverRepository.existsByAssetNo(req.getAssetNo())) {
            throw new BizException("资产编号已存在：" + req.getAssetNo());
        }
        if (!IdcRule.isValidServerStatus(req.getStatus())) {
            throw new BizException("服务器状态不合法：" + req.getStatus());
        }
        ServerAsset server = new ServerAsset();
        apply(server, req);
        checkPlacement(server, null);
        return serverRepository.save(server);
    }

    public ServerAsset update(Long id, Dtos.ServerSaveReq req) {
        ServerAsset server = serverRepository.findById(id).orElseThrow(() -> new BizException("服务器不存在"));
        ServerAsset other = new ServerAsset();
        other.setAssetNo(req.getAssetNo());
        List<ServerAsset> sameNo = serverRepository.findAll(Example.of(other, ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withMatcher("assetNo", ExampleMatcher.GenericPropertyMatchers.exact())));
        if (sameNo.stream().anyMatch(row -> !row.getId().equals(id))) {
            throw new BizException("资产编号已存在：" + req.getAssetNo());
        }
        if (!IdcRule.isValidServerStatus(req.getStatus())) {
            throw new BizException("服务器状态不合法：" + req.getStatus());
        }
        apply(server, req);
        checkPlacement(server, id);
        return serverRepository.save(server);
    }

    /** 状态变更：在架/下架/维修/报废 */
    public ServerAsset changeStatus(Long id, Dtos.ServerStatusReq req) {
        ServerAsset server = serverRepository.findById(id).orElseThrow(() -> new BizException("服务器不存在"));
        if (!IdcRule.isValidServerStatus(req.getStatus())) {
            throw new BizException("服务器状态不合法：" + req.getStatus());
        }
        if ("SCRAP".equals(server.getStatus()) && !"SCRAP".equals(req.getStatus())) {
            throw new BizException("已报废的服务器不能再变更状态");
        }
        server.setStatus(req.getStatus());
        checkPlacement(server, id);
        return serverRepository.save(server);
    }

    public ServerAsset get(Long id) {
        return serverRepository.findById(id).orElseThrow(() -> new BizException("服务器不存在"));
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

    /** 只有占 U 位的状态才校验位置冲突；下架的不占位，允许没有机柜 */
    private void checkPlacement(ServerAsset server, Long excludeId) {
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
