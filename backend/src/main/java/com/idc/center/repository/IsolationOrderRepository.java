package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.IsolationOrder;

/**
 * 隔离冻结施工快照：只随生效写一次，之后只读。
 */
@Repository
public interface IsolationOrderRepository extends JpaRepository<IsolationOrder, Long> {

    List<IsolationOrder> findByIsolationId(Long isolationId);

    long countByIsolationId(Long isolationId);
}
