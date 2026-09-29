package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.ServerAsset;

/**
 * 服务器资产：多条件筛选走 Spring Data JPA 的 Query by Example，
 * 直接用父接口提供的 findAll(Example&lt;ServerAsset&gt;, Pageable)。
 */
@Repository
public interface ServerAssetRepository extends JpaRepository<ServerAsset, Long> {

    List<ServerAsset> findByCabinetIdOrderByStartUAsc(Long cabinetId);

    boolean existsByAssetNo(String assetNo);

    /** 工单落地时锁服务器行，避免同一台服务器被两张工单同时改位置 / 状态 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServerAsset s where s.id = :id")
    Optional<ServerAsset> lockById(@Param("id") Long id);
}
