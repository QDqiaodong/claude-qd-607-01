package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.Cabinet;

import jakarta.persistence.LockModeType;

@Repository
public interface CabinetRepository extends JpaRepository<Cabinet, Long> {

    List<Cabinet> findByRoomIdOrderByCodeAsc(Long roomId);

    boolean existsByCode(String code);

    /** 工单落地前对相关机柜上的行锁（SELECT ... FOR UPDATE），同一机柜的占位争用串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cabinet c where c.id = :id")
    Optional<Cabinet> lockById(@Param("id") Long id);
}
