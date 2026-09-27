package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
