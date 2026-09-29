package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.WorkOrder;

import jakarta.persistence.LockModeType;

/**
 * 工单：列表同样走 Query by Example（按类型/状态精确匹配，单号、申请人模糊匹配）。
 */
@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    List<WorkOrder> findByServerIdOrderByIdDesc(Long serverId);

    boolean existsByOrderNo(String order);

    /** 机柜相关（目标机柜）某状态的上架 / 迁移工单：隔离生效时查施工中的冲突 */
    List<WorkOrder> findByCabinetIdAndOrderTypeInAndStatus(Long cabinetId, List<String> orderTypes, String status);

    /** 机柜相关某几个状态的上架 / 迁移工单：隔离生效时冻结待审批 / 已批复 */
    List<WorkOrder> findByCabinetIdAndOrderTypeInAndStatusIn(Long cabinetId, List<String> orderTypes, List<String> statuses);

    /** 推进前的行锁读取（SELECT ... FOR UPDATE），同一张工单的并发推进串行化，只能完成一次 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.id = :id")
    Optional<WorkOrder> lockById(@Param("id") Long id);
}
