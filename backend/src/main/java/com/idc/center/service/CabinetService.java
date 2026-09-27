package com.idc.center.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Room;
import com.idc.center.domain.ServerAsset;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.RoomRepository;
import com.idc.center.repository.ServerAssetRepository;

/**
 * 模块一：机房 → 机柜，以及机架 U 位图的拼装。
 */
@Service
public class CabinetService {

    private final RoomRepository roomRepository;
    private final CabinetRepository cabinetRepository;
    private final ServerAssetRepository serverRepository;

    public CabinetService(RoomRepository roomRepository,
                          CabinetRepository cabinetRepository,
                          ServerAssetRepository serverRepository) {
        this.roomRepository = roomRepository;
        this.cabinetRepository = cabinetRepository;
        this.serverRepository = serverRepository;
    }

    public List<Room> listRooms() {
        return roomRepository.findAll(Sort.by("code"));
    }

    public Room createRoom(Dtos.RoomSaveReq req) {
        if (roomRepository.existsByCode(req.getCode())) {
            throw new BizException("机房编号已存在：" + req.getCode());
        }
        Room room = new Room();
        room.setCode(req.getCode().trim());
        room.setName(req.getName().trim());
        room.setFloorNo(req.getFloorNo());
        room.setAreaSqm(req.getAreaSqm());
        room.setPowerCapacity(req.getPowerCapacity());
        room.setDutyPerson(req.getDutyPerson());
        room.setRemark(req.getRemark());
        return roomRepository.save(room);
    }

    /** 机柜列表：带机房名与已用 U 数，左侧树和下拉框都用它 */
    public List<Views.CabinetView> listCabinets(Long roomId) {
        List<Cabinet> cabinets = roomId == null
                ? cabinetRepository.findAll(Sort.by("code"))
                : cabinetRepository.findByRoomIdOrderByCodeAsc(roomId);
        Map<Long, String> roomNames = new HashMap<>();
        for (Room room : roomRepository.findAll()) {
            roomNames.put(room.getId(), room.getName());
        }
        List<Views.CabinetView> views = new ArrayList<>();
        for (Cabinet cabinet : cabinets) {
            views.add(Views.CabinetView.of(cabinet, roomNames.get(cabinet.getRoomId()), usedU(cabinet.getId())));
        }
        return views;
    }

    public Cabinet createCabinet(Dtos.CabinetSaveReq req) {
        Room room = roomRepository.findById(req.getRoomId())
                .orElseThrow(() -> new BizException("机房不存在"));
        if (cabinetRepository.existsByCode(req.getCode())) {
            throw new BizException("机柜编号已存在：" + req.getCode());
        }
        Cabinet cabinet = new Cabinet();
        cabinet.setRoomId(room.getId());
        cabinet.setCode(req.getCode().trim());
        cabinet.setName(req.getName() == null || req.getName().isBlank()
                ? room.getName() + "-" + req.getCode() : req.getName().trim());
        cabinet.setRowNo(req.getRowNo());
        cabinet.setColNo(req.getColNo());
        cabinet.setTotalU(req.getTotalU());
        cabinet.setRatedPower(req.getRatedPower());
        cabinet.setRemark(req.getRemark());
        return cabinetRepository.save(cabinet);
    }

    /** 机架 U 位图：从最上面的 U 往下画，服务器占几个 U 就跨几格 */
    public Views.RackView rack(Long cabinetId) {
        Cabinet cabinet = cabinetRepository.findById(cabinetId)
                .orElseThrow(() -> new BizException("机柜不存在"));
        Room room = cabinet.getRoomId() == null ? null : roomRepository.findById(cabinet.getRoomId()).orElse(null);
        int totalU = cabinet.getTotalU() == null ? 42 : cabinet.getTotalU();

        List<ServerAsset> servers = serverRepository.findByCabinetIdOrderByStartUAsc(cabinetId);
        ServerAsset[] slotOwner = new ServerAsset[totalU + 2];
        for (ServerAsset server : servers) {
            if (!IdcRule.occupiesU(server.getStatus()) || server.getStartU() == null || server.getStartU() < 1) {
                continue;
            }
            int size = server.getSizeU() == null || server.getSizeU() < 1 ? 1 : server.getSizeU();
            for (int u = server.getStartU(); u < server.getStartU() + size && u <= totalU; u++) {
                slotOwner[u] = server;
            }
        }

        List<Views.RackSlot> slots = new ArrayList<>();
        for (int u = totalU; u >= 1; u--) {
            ServerAsset owner = slotOwner[u];
            if (owner == null) {
                slots.add(Views.RackSlot.free(u));
            } else if (owner.getStartU() == u) {
                slots.add(Views.RackSlot.of(owner));
            }
            // 其余 u 属于某台服务器的上半截，已经被跨行格子覆盖，不再单独画
        }

        Views.RackView view = new Views.RackView();
        view.setCabinetId(cabinet.getId());
        view.setCabinetCode(cabinet.getCode());
        view.setCabinetName(cabinet.getName());
        view.setRoomId(cabinet.getRoomId());
        view.setRoomName(room == null ? "" : room.getName());
        view.setTotalU(totalU);
        view.setRatedPower(cabinet.getRatedPower());
        view.setUsedU(usedU(cabinet.getId(), servers));
        view.setFreeU(totalU - view.getUsedU());
        view.setSlots(slots);
        return view;
    }

    /** 已用 U 数 */
    private Integer usedU(Long cabinetId) {
        return usedU(cabinetId, serverRepository.findByCabinetIdOrderByStartUAsc(cabinetId));
    }

    private Integer usedU(Long cabinetId, List<ServerAsset> servers) {
        int used = 0;
        for (ServerAsset server : servers) {
            if (IdcRule.occupiesU(server.getStatus())) {
                used += server.getSizeU() == null ? 1 : server.getSizeU();
            }
        }
        return used;
    }
}
