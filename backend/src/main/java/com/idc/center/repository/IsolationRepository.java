package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.Isolation;

import jakarta.persistence.LockModeType;

/**
 * 停电检修隔离：台账列表走 Query by Example（状态精确、单号模糊）。
 */
@Repository
public interface IsolationRepository extends JpaRepository<Isolation, Long> {

    List<Isolation> findByStatus(String status);

    /** 机柜当前生效中的隔离（active_cabinet 仅隔离中非空，且有唯一索引） */
    Optional<Isolation> findByActiveCabinet(Long cabinetId);

    /** 生效 / 解除 / 取消前的行锁读取（SELECT ... FOR UPDATE），同一记录的并发操作串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Isolation i where i.id = :id")
    Optional<Isolation> lockById(@Param("id") Long id);
}
