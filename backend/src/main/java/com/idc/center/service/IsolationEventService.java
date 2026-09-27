package com.idc.center.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.domain.IsolationEvent;
import com.idc.center.repository.IsolationEventRepository;

/**
 * 隔离操作留痕。生效失败用独立事务提交：主事务回滚后失败原因仍然可查，
 * 值班人员可以按原因处理后再次操作。
 */
@Service
public class IsolationEventService {

    private final IsolationEventRepository eventRepository;

    public IsolationEventService(IsolationEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * 生效失败留痕（REQUIRES_NEW）。注意：只写事件表，不碰隔离主记录 ——
     * 调用时主事务往往还持有主记录行锁，写主记录会锁等待。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long isolationId, String operator, String reason) {
        IsolationEvent event = new IsolationEvent();
        event.setIsolationId(isolationId);
        event.setAction("ACTIVATE_FAIL");
        event.setOperator(operator);
        event.setDetail("生效失败：" + reason);
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);
    }
}
