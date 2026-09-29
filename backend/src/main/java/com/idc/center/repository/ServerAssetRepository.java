package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.ServerAsset;

import jakarta.persistence.LockModeType;

/**
 * 服务器资产：多条件筛选走 Spring Data JPA 的 Query by Example，
 * 直接用父接口提供的 findAll(Example&lt;ServerAsset&gt;, Pageable)。
 */
@Repository
public interface ServerAssetRepository extends JpaRepository<ServerAsset, Long> {

    List<ServerAsset> findByCabinetIdOrderByStartUAsc(Long cabinetId);

    /** 工单落地 / 手工改位置前的行锁读取，与同机柜的上架、迁移、下架串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServerAsset s where s.id = :id")
    Optional<ServerAsset> lockById(@Param("id") Long id);

    boolean existsByAssetNo(String assetNo);
}
