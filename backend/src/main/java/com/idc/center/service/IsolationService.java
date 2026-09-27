package com.idc.center.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.IsolationOrder;
import com.idc.center.domain.IsolationServer;
import com.idc.center.domain.Room;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.IsolationEventRepository;
import com.idc.center.repository.IsolationOrderRepository;
import com.idc.center.repository.IsolationRepository;
import com.idc.center.repository.IsolationServerRepository;
import com.idc.center.repository.RoomRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块六：机柜停电检修隔离。台账、详情聚合（快照 + 当前差异 + 冲突 + 留痕）。
 * 状态机写操作在 {@link IsolationTxService}，这里负责校验、组装与并发冲突的翻译。
 */
@Service
public class IsolationService {

    private static final DateTimeFormatter NO_SEQ = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final List<String> FROZEN_TYPES = List.of("ON", "MOVE");

    private final IsolationRepository isolationRepository;
    private final IsolationServerRepository isolationServerRepository;
    private final IsolationOrderRepository isolationOrderRepository;
    private final IsolationEventRepository isolationEventRepository;
    private final CabinetRepository cabinetRepository;
    private final RoomRepository roomRepository;
    private final ServerAssetRepository serverRepository;
    private final WorkOrderRepository workOrderRepository;
    private final IsolationTxService txService;
    private final IsolationEventService eventService;

    public IsolationService(IsolationRepository isolationRepository,
                            IsolationServerRepository isolationServerRepository,
                            IsolationOrderRepository isolationOrderRepository,
                            IsolationEventRepository isolationEventRepository,
                            CabinetRepository cabinetRepository,
                            RoomRepository roomRepository,
                            ServerAssetRepository serverRepository,
                            WorkOrderRepository workOrderRepository,
                            IsolationTxService txService,
                            IsolationEventService eventService) {
        this.isolationRepository = isolationRepository;
        this.isolationServerRepository = isolationServerRepository;
        this.isolationOrderRepository = isolationOrderRepository;
        this.isolationEventRepository = isolationEventRepository;
        this.cabinetRepository = cabinetRepository;
        this.roomRepository = roomRepository;
        this.serverRepository = serverRepository;
        this.workOrderRepository = workOrderRepository;
        this.txService = txService;
        this.eventService = eventService;
    }

    /** 台账：单号模糊、状态与机柜精确（走 Example），按单号倒序 */
    public Views.PageResult<Views.IsolationView> search(Dtos.IsolationQuery query) {
        Isolation probe = new Isolation();
        probe.setIsolationNo(ExamplePage.blankToNull(query.getIsolationNo()));
        probe.setStatus(ExamplePage.blankToNull(query.getStatus()));
        probe.setCabinetId(query.getCabinetId());

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING)
                .withMatcher("status", ExampleMatcher.GenericPropertyMatchers.exact());

        int size = query.getSize() < 1 ? 10 : query.getSize();
        org.springframework.data.domain.Page<Isolation> page = isolationRepository.findAll(
                Example.of(probe, matcher),
                PageRequest.of(Math.max(query.getPage(), 0), size, Sort.by(Sort.Direction.DESC, "id")));

        Map<Long, Cabinet> cabinets = cabinetsById();
        Map<Long, String> roomNames = roomNames();
        List<Views.IsolationView> views = new ArrayList<>();
        for (Isolation iso : page.getContent()) {
            views.add(toView(iso, cabinets, roomNames));
        }
        return Views.PageResult.of(views, page.getTotalElements(), Math.max(query.getPage(), 0), size);
    }

    @Transactional
    public Isolation create(Dtos.IsolationCreateReq req) {
        Cabinet cabinet = cabinetRepository.findById(req.getCabinetId())
                .orElseThrow(() -> new BizException("机柜不存在"));
        if (!req.getPlanEnd().isAfter(req.getPlanStart())) {
            throw new BizException("计划结束时间必须晚于计划开始时间");
        }
        Isolation iso = new Isolation();
        iso.setIsolationNo(nextNo());
        iso.setCabinetId(cabinet.getId());
        iso.setPlanStart(req.getPlanStart());
        iso.setPlanEnd(req.getPlanEnd());
        iso.setReason(req.getReason().trim());
        iso.setInitiator(req.getInitiator().trim());
        iso.setManager(req.getManager().trim());
        iso.setStatus("PENDING");
        iso.setCreatedAt(LocalDateTime.now());
        Isolation saved = isolationRepository.save(iso);

        txService.recordCreate(saved.getId(), saved.getInitiator(),
                "创建隔离记录，计划 " + IsolationTxService.minuteText(saved.getPlanStart())
                        + " ~ " + IsolationTxService.minuteText(saved.getPlanEnd()) + " 断电检修");
        return saved;
    }

    /**
     * 生效。并发生效被唯一索引拦住时（DataIntegrityViolationException），
     * 查出已经生效的那条记录一并告诉值班人员，并留下失败留痕。
     */
    public Isolation activate(Long id, String operator) {
        try {
            return txService.activate(id, operator);
        } catch (DataIntegrityViolationException e) {
            String message = "该机柜已存在生效中的隔离记录，同一机柜同一时刻只允许一条隔离中记录";
            Isolation self = isolationRepository.findById(id).orElse(null);
            if (self != null) {
                Isolation active = isolationRepository.findByActiveCabinet(self.getCabinetId()).orElse(null);
                if (active != null) {
                    message = "该机柜已存在生效中的隔离记录「" + active.getIsolationNo()
                            + "」（发起人 " + active.getInitiator()
                            + "，计划 " + IsolationTxService.minuteText(active.getPlanStart())
                            + " ~ " + IsolationTxService.minuteText(active.getPlanEnd())
                            + "），同一机柜同一时刻只允许一条隔离中记录";
                }
                eventService.recordFailure(id, operator, message);
            }
            throw new BizException(message);
        }
    }

    public Isolation release(Long id, Dtos.IsolationReleaseReq req) {
        return txService.release(id, req);
    }

    public Isolation cancel(Long id, Dtos.IsolationOperateReq req) {
        return txService.cancel(id, req);
    }

    /** 详情：计划信息 + 隔离时快照 + 当前差异 + 实时冲突施工 + 操作记录 */
    public Views.IsolationDetail detail(Long id) {
        Isolation iso = isolationRepository.findById(id).orElseThrow(() -> new BizException("隔离记录不存在"));
        Map<Long, Cabinet> cabinets = cabinetsById();
        Map<Long, String> roomNames = roomNames();
        Cabinet cabinet = cabinets.get(iso.getCabinetId());

        Views.IsolationDetail detail = new Views.IsolationDetail();
        detail.setIsolation(iso);
        detail.setCabinetCode(cabinet == null ? "" : cabinet.getCode());
        detail.setCabinetName(cabinet == null ? "" : cabinet.getName());
        detail.setRoomName(cabinet == null ? "" : roomNames.getOrDefault(cabinet.getRoomId(), ""));
        detail.setServerDiffs(serverDiffs(iso));
        detail.setFrozenOrders(orderDiffs(iso));
        detail.setConflicts(conflicts(iso.getCabinetId()));
        detail.setEvents(isolationEventRepository.findByIsolationIdOrderByIdAsc(iso.getId()));
        return detail;
    }

    /** 机柜当前生效中的隔离（机柜页横幅用），无则 null */
    public Views.IsolationView currentByCabinet(Long cabinetId) {
        return isolationRepository.findByActiveCabinet(cabinetId)
                .map(iso -> toView(iso, cabinetsById(), roomNames()))
                .orElse(null);
    }

    /** 全部隔离中的记录（工单页标记冻结用） */
    public List<Views.IsolationView> activeList() {
        Map<Long, Cabinet> cabinets = cabinetsById();
        Map<Long, String> roomNames = roomNames();
        return isolationRepository.findByStatus("ACTIVE").stream()
                .map(iso -> toView(iso, cabinets, roomNames))
                .toList();
    }

    /** 快照 vs 当前：历史快照不动，差异逐台算出来给页面；未生效过的记录没有快照，不算差异 */
    private List<Views.ServerDiff> serverDiffs(Isolation iso) {
        List<Views.ServerDiff> diffs = new ArrayList<>();
        if (!"ACTIVE".equals(iso.getStatus()) && !"RELEASED".equals(iso.getStatus())) {
            return diffs;
        }
        List<IsolationServer> snaps = isolationServerRepository.findByIsolationIdOrderByStartUAsc(iso.getId());
        List<ServerAsset> currentInCabinet = serverRepository.findByCabinetIdOrderByStartUAsc(iso.getCabinetId());
        Map<Long, ServerAsset> currentById = new HashMap<>();
        for (ServerAsset server : serverRepository.findAllById(
                snaps.stream().map(IsolationServer::getServerId).toList())) {
            currentById.put(server.getId(), server);
        }

        Set<Long> snapIds = new HashSet<>();
        for (IsolationServer snap : snaps) {
            snapIds.add(snap.getServerId());
            Views.ServerDiff diff = new Views.ServerDiff();
            diff.setServerId(snap.getServerId());
            diff.setAssetNo(snap.getAssetNo());
            diff.setBrand(snap.getBrand());
            diff.setModel(snap.getModel());
            diff.setSnapStartU(snap.getStartU());
            diff.setSnapSizeU(snap.getSizeU());
            diff.setSnapOwnerName(snap.getOwnerName());
            diff.setSnapStatus(snap.getStatus());

            ServerAsset current = currentById.get(snap.getServerId());
            if (current == null) {
                diff.setDiffType("GONE");
            } else {
                diff.setCurrentCabinetId(current.getCabinetId());
                diff.setCurrentStartU(current.getStartU());
                diff.setCurrentSizeU(current.getSizeU());
                diff.setCurrentOwnerName(current.getOwnerName());
                diff.setCurrentStatus(current.getStatus());
                if (!iso.getCabinetId().equals(current.getCabinetId())) {
                    diff.setDiffType("MOVED_OUT");
                } else if (samePlace(snap, current)) {
                    diff.setDiffType("SAME");
                } else {
                    diff.setDiffType("CHANGED");
                }
            }
            diffs.add(diff);
        }
        // 隔离生效后才进入本柜（在架 / 维修中）的服务器：不属于历史快照，但作为当前差异提示
        for (ServerAsset server : currentInCabinet) {
            if (snapIds.contains(server.getId()) || !IdcRule.inIsolationScope(server.getStatus())) {
                continue;
            }
            Views.ServerDiff diff = new Views.ServerDiff();
            diff.setServerId(server.getId());
            diff.setAssetNo(server.getAssetNo());
            diff.setBrand(server.getBrand());
            diff.setModel(server.getModel());
            diff.setCurrentCabinetId(server.getCabinetId());
            diff.setCurrentStartU(server.getStartU());
            diff.setCurrentSizeU(server.getSizeU());
            diff.setCurrentOwnerName(server.getOwnerName());
            diff.setCurrentStatus(server.getStatus());
            diff.setDiffType("ADDED");
            diffs.add(diff);
        }
        return diffs;
    }

    private boolean samePlace(IsolationServer snap, ServerAsset current) {
        return eq(snap.getStartU(), current.getStartU())
                && eq(snap.getSizeU(), current.getSizeU())
                && eq(snap.getOwnerName(), current.getOwnerName())
                && eq(snap.getStatus(), current.getStatus());
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 冻结施工快照 vs 工单当前状态 */
    private List<Views.OrderDiff> orderDiffs(Isolation iso) {
        List<IsolationOrder> frozen = isolationOrderRepository.findByIsolationId(iso.getId());
        Map<Long, WorkOrder> orderById = new HashMap<>();
        for (WorkOrder order : workOrderRepository.findAllById(
                frozen.stream().map(IsolationOrder::getOrderId).toList())) {
            orderById.put(order.getId(), order);
        }
        List<Views.OrderDiff> diffs = new ArrayList<>();
        for (IsolationOrder snap : frozen) {
            Views.OrderDiff diff = new Views.OrderDiff();
            diff.setOrderId(snap.getOrderId());
            diff.setOrderNo(snap.getOrderNo());
            diff.setOrderType(snap.getOrderType());
            diff.setAssetNo(snap.getAssetNo());
            diff.setTargetU(snap.getTargetU());
            diff.setApplicant(snap.getApplicant());
            diff.setStatusAtFreeze(snap.getStatusAtFreeze());
            WorkOrder current = orderById.get(snap.getOrderId());
            diff.setCurrentStatus(current == null ? null : current.getStatus());
            diffs.add(diff);
        }
        return diffs;
    }

    /** 实时冲突：机柜相关且已进入施工中的上架 / 迁移工单 */
    private List<Views.ConflictOrder> conflicts(Long cabinetId) {
        List<Views.ConflictOrder> result = new ArrayList<>();
        for (WorkOrder order : workOrderRepository.findByCabinetIdAndOrderTypeInAndStatus(
                cabinetId, FROZEN_TYPES, "WORKING")) {
            Views.ConflictOrder conflict = new Views.ConflictOrder();
            conflict.setOrderId(order.getId());
            conflict.setOrderNo(order.getOrderNo());
            conflict.setOrderType(order.getOrderType());
            conflict.setAssetNo(order.getAssetNo());
            conflict.setApplicant(order.getApplicant());
            conflict.setStatus(order.getStatus());
            result.add(conflict);
        }
        return result;
    }

    private Views.IsolationView toView(Isolation iso, Map<Long, Cabinet> cabinets, Map<Long, String> roomNames) {
        Cabinet cabinet = cabinets.get(iso.getCabinetId());
        String roomName = cabinet == null ? "" : roomNames.getOrDefault(cabinet.getRoomId(), "");
        return Views.IsolationView.of(iso, cabinet, roomName,
                (int) isolationServerRepository.countByIsolationId(iso.getId()),
                (int) isolationOrderRepository.countByIsolationId(iso.getId()));
    }

    private Map<Long, Cabinet> cabinetsById() {
        Map<Long, Cabinet> map = new HashMap<>();
        for (Cabinet cabinet : cabinetRepository.findAll()) {
            map.put(cabinet.getId(), cabinet);
        }
        return map;
    }

    private Map<Long, String> roomNames() {
        Map<Long, String> map = new HashMap<>();
        for (Room room : roomRepository.findAll()) {
            map.put(room.getId(), room.getName());
        }
        return map;
    }

    /** 单号：ISO + 秒级时间戳 + 三位随机，唯一索引兜底 */
    private String nextNo() {
        return "ISO" + NO_SEQ.format(LocalDateTime.now()) + (100 + ThreadLocalRandom.current().nextInt(900));
    }
}
