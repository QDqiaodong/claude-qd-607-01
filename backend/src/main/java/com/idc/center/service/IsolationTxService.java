package com.idc.center.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.IsolationEvent;
import com.idc.center.domain.IsolationOrder;
import com.idc.center.domain.IsolationServer;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.repository.IsolationEventRepository;
import com.idc.center.repository.IsolationOrderRepository;
import com.idc.center.repository.IsolationRepository;
import com.idc.center.repository.IsolationServerRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块六：隔离状态机的事务核心（生效 / 解除 / 取消）。
 * 每个方法一个事务：快照、冻结、状态翻转要么一起落库，要么整体回滚 ——
 * 不会出现「隔离已生效但施工仍可推进」或「快照生成了但状态没翻」的半完成状态。
 */
@Service
public class IsolationTxService {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> FROZEN_TYPES = List.of("ON", "MOVE");

    private final IsolationRepository isolationRepository;
    private final IsolationServerRepository isolationServerRepository;
    private final IsolationOrderRepository isolationOrderRepository;
    private final IsolationEventRepository isolationEventRepository;
    private final ServerAssetRepository serverRepository;
    private final WorkOrderRepository workOrderRepository;
    private final IsolationEventService eventService;

    public IsolationTxService(IsolationRepository isolationRepository,
                              IsolationServerRepository isolationServerRepository,
                              IsolationOrderRepository isolationOrderRepository,
                              IsolationEventRepository isolationEventRepository,
                              ServerAssetRepository serverRepository,
                              WorkOrderRepository workOrderRepository,
                              IsolationEventService eventService) {
        this.isolationRepository = isolationRepository;
        this.isolationServerRepository = isolationServerRepository;
        this.isolationOrderRepository = isolationOrderRepository;
        this.isolationEventRepository = isolationEventRepository;
        this.serverRepository = serverRepository;
        this.workOrderRepository = workOrderRepository;
        this.eventService = eventService;
    }

    /**
     * 生效：行锁 → 冲突检查 → 固定服务器快照 → 冻结施工快照 → 状态翻转，同一事务。
     * 重复提交 / 生效后刷新：已是隔离中则直接返回，不重复生成任何明细。
     * 同机柜两条记录并发生效：active_cabinet 唯一索引只放一条过去，
     * 另一条在 saveAndFlush 时抛 DataIntegrityViolationException 整体回滚。
     */
    @Transactional
    public Isolation activate(Long id, String operator) {
        Isolation iso = isolationRepository.lockById(id)
                .orElseThrow(() -> new BizException("隔离记录不存在"));
        if ("ACTIVE".equals(iso.getStatus())) {
            return iso;
        }
        if (!"PENDING".equals(iso.getStatus())) {
            throw new BizException("当前状态（" + IdcRule.isolationStatusName(iso.getStatus()) + "）不能执行生效");
        }

        // 冲突检查：已进入施工中的上架 / 迁移工单必须人工收口，系统不改它的状态，只阻止生效
        List<WorkOrder> conflicts = workOrderRepository.findByCabinetIdAndOrderTypeInAndStatus(
                iso.getCabinetId(), FROZEN_TYPES, "WORKING");
        if (!conflicts.isEmpty()) {
            String message = "该机柜存在 " + conflicts.size() + " 张施工中工单（"
                    + conflicts.stream().map(WorkOrder::getOrderNo).collect(Collectors.joining("、"))
                    + "），需完工闭环后才能生效，请处理后重试";
            eventService.recordFailure(iso.getId(), operator, message);
            throw new BizException(message);
        }

        // 固定受影响服务器清单：当时仍在架或维修中的，连同 U 位、业务归属、状态一起快照
        int serverCount = 0;
        if (isolationServerRepository.countByIsolationId(iso.getId()) == 0) {
            List<ServerAsset> servers = serverRepository.findByCabinetIdOrderByStartUAsc(iso.getCabinetId())
                    .stream()
                    .filter(server -> IdcRule.inIsolationScope(server.getStatus()))
                    .toList();
            for (ServerAsset server : servers) {
                IsolationServer snap = new IsolationServer();
                snap.setIsolationId(iso.getId());
                snap.setServerId(server.getId());
                snap.setAssetNo(server.getAssetNo());
                snap.setBrand(server.getBrand());
                snap.setModel(server.getModel());
                snap.setStartU(server.getStartU());
                snap.setSizeU(server.getSizeU());
                snap.setOwnerName(server.getOwnerName());
                snap.setStatus(server.getStatus());
                isolationServerRepository.save(snap);
            }
            serverCount = servers.size();
        } else {
            serverCount = (int) isolationServerRepository.countByIsolationId(iso.getId());
        }

        // 冻结待审批 / 已批复的上架、迁移施工：只快照事实，工单能否推进由实时校验把关
        int frozenCount = 0;
        if (isolationOrderRepository.countByIsolationId(iso.getId()) == 0) {
            List<WorkOrder> frozen = workOrderRepository.findByCabinetIdAndOrderTypeInAndStatusIn(
                    iso.getCabinetId(), FROZEN_TYPES, List.of("PENDING", "APPROVED"));
            for (WorkOrder order : frozen) {
                IsolationOrder snap = new IsolationOrder();
                snap.setIsolationId(iso.getId());
                snap.setOrderId(order.getId());
                snap.setOrderNo(order.getOrderNo());
                snap.setOrderType(order.getOrderType());
                snap.setAssetNo(order.getAssetNo());
                snap.setTargetU(order.getTargetU());
                snap.setApplicant(order.getApplicant());
                snap.setStatusAtFreeze(order.getStatus());
                isolationOrderRepository.save(snap);
            }
            frozenCount = frozen.size();
        } else {
            frozenCount = (int) isolationOrderRepository.countByIsolationId(iso.getId());
        }

        iso.setStatus("ACTIVE");
        iso.setActiveCabinet(iso.getCabinetId());
        iso.setActivatedAt(LocalDateTime.now());
        isolationRepository.saveAndFlush(iso);

        saveEvent(iso.getId(), "ACTIVATE", operator,
                "隔离生效，固定受影响服务器 " + serverCount + " 台，冻结施工工单 " + frozenCount + " 张");
        return iso;
    }

    /** 解除：现场负责人填写解除说明后收口；可安全重试，已解除的直接返回 */
    @Transactional
    public Isolation release(Long id, Dtos.IsolationReleaseReq req) {
        Isolation iso = isolationRepository.lockById(id)
                .orElseThrow(() -> new BizException("隔离记录不存在"));
        if ("RELEASED".equals(iso.getStatus())) {
            return iso;
        }
        if (!"ACTIVE".equals(iso.getStatus())) {
            throw new BizException("只有隔离中的记录可以解除，当前状态（"
                    + IdcRule.isolationStatusName(iso.getStatus()) + "）不允许解除");
        }
        String operator = req.getOperator().trim();
        if (!operator.equals(iso.getManager())) {
            throw new BizException("只能由现场负责人「" + iso.getManager() + "」办理解除");
        }
        iso.setStatus("RELEASED");
        iso.setActiveCabinet(null);
        iso.setReleasedAt(LocalDateTime.now());
        iso.setReleaseOperator(operator);
        iso.setReleaseNote(req.getNote().trim());
        isolationRepository.save(iso);

        saveEvent(iso.getId(), "RELEASE", operator,
                "解除隔离：" + iso.getReleaseNote() + "；相关施工恢复正常推进");
        return iso;
    }

    /** 取消：只有待生效可以取消；重复取消安全返回 */
    @Transactional
    public Isolation cancel(Long id, Dtos.IsolationOperateReq req) {
        Isolation iso = isolationRepository.lockById(id)
                .orElseThrow(() -> new BizException("隔离记录不存在"));
        if ("CANCELED".equals(iso.getStatus())) {
            return iso;
        }
        if (!"PENDING".equals(iso.getStatus())) {
            throw new BizException("只有待生效的记录可以取消，当前状态（"
                    + IdcRule.isolationStatusName(iso.getStatus()) + "）不允许取消");
        }
        String operator = req == null || req.getOperator() == null || req.getOperator().isBlank()
                ? "值班人员" : req.getOperator().trim();
        iso.setStatus("CANCELED");
        iso.setCanceledAt(LocalDateTime.now());
        iso.setCancelOperator(operator);
        iso.setCancelReason(req == null || req.getReason() == null || req.getReason().isBlank()
                ? null : req.getReason().trim());
        isolationRepository.save(iso);

        String detail = "取消隔离";
        if (iso.getCancelReason() != null) {
            detail += "：" + iso.getCancelReason();
        }
        saveEvent(iso.getId(), "CANCEL", operator, detail);
        return iso;
    }

    /** 创建留痕（与创建同事务） */
    @Transactional
    public void recordCreate(Long isolationId, String operator, String detail) {
        saveEvent(isolationId, "CREATE", operator, detail);
    }

    private void saveEvent(Long isolationId, String action, String operator, String detail) {
        IsolationEvent event = new IsolationEvent();
        event.setIsolationId(isolationId);
        event.setAction(action);
        event.setOperator(operator == null || operator.isBlank() ? "值班人员" : operator.trim());
        event.setDetail(detail);
        event.setCreatedAt(LocalDateTime.now());
        isolationEventRepository.save(event);
    }

    public static String minuteText(LocalDateTime time) {
        return time == null ? "" : MINUTE.format(time);
    }
}
