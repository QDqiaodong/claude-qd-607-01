package com.idc.center.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 隔离操作留痕：创建 / 生效 / 生效失败 / 取消 / 解除。
 * 生效失败也留痕（独立事务提交），主记录回滚不丢失败原因。
 */
@Entity
@Table(name = "idc_isolation_event")
@Data
public class IsolationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 隔离记录 */
    @Column(name = "isolation_id", nullable = false)
    private Long isolationId;

    /** 动作：CREATE / ACTIVATE / ACTIVATE_FAIL / CANCEL / RELEASE */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    /** 操作人 */
    @Column(name = "operator", length = 30)
    private String operator;

    /** 操作说明 */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
