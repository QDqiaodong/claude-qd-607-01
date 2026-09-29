package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.Cabinet;

@Repository
public interface CabinetRepository extends JpaRepository<Cabinet, Long> {

    List<Cabinet> findByRoomIdOrderByCodeAsc(Long roomId);

    boolean existsByCode(String code);

    /** U 位争用串行化：所有落地到该机柜的上架 / 迁移、以及从该机柜下架都先锁柜行（FOR UPDATE） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cabinet c where c.id = :id")
    Optional<Cabinet> lockById(@Param("id") Long id);
}
