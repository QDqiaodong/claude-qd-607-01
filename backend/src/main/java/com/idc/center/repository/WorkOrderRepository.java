package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.WorkOrder;

/**
 * 工单：列表同样走 Query by Example（按类型/状态精确匹配，单号、申请人模糊匹配）。
 */
@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    List<WorkOrder> findByServerIdOrderByIdDesc(Long serverId);

    boolean existsByOrderNo(String orderNo);

    /** 机柜相关（目标机柜）某状态的上架 / 迁移工单：隔离生效时查施工中的冲突 */
    List<WorkOrder> findByCabinetIdAndOrderTypeInAndStatus(Long cabinetId, List<String> orderTypes, String status);

    /** 机柜相关某几个状态的上架 / 迁移工单：隔离生效时冻结待审批 / 已批复 */
    List<WorkOrder> findByCabinetIdAndOrderTypeInAndStatusIn(Long cabinetId, List<String> orderTypes, List<String> statuses);
}
