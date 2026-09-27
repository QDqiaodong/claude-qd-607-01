package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.IsolationEvent;

/**
 * 隔离操作留痕。
 */
@Repository
public interface IsolationEventRepository extends JpaRepository<IsolationEvent, Long> {

    List<IsolationEvent> findByIsolationIdOrderByIdAsc(Long isolationId);
}
