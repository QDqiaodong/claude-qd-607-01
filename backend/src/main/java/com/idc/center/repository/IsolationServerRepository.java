package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.IsolationServer;

/**
 * 隔离影响服务器快照：只随生效写一次，之后只读。
 */
@Repository
public interface IsolationServerRepository extends JpaRepository<IsolationServer, Long> {

    List<IsolationServer> findByIsolationIdOrderByStartUAsc(Long isolationId);

    long countByIsolationId(Long isolationId);
}
