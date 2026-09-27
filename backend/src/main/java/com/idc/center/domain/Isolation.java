package com.idc.center.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块六：机柜停电检修隔离主记录。
 * 状态流转：PENDING 待生效 → ACTIVE 隔离中 → RELEASED 已解除；
 * 待生效可以取消成 CANCELED 已取消。
 * activeCabinet 仅在隔离中 = 机柜ID，靠唯一索引保证同机柜任一时刻只有一条隔离中。
 */
@Entity
@Table(name = "idc_isolation")
@DynamicUpdate
@Data
public class Isolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 隔离单号 */
    @Column(name = "isolation_no", nullable = false, length = 30)
    private String isolationNo;

    /** 隔离机柜 */
    @Column(name = "cabinet_id", nullable = false)
    private Long cabinetId;

    /** 计划开始时间 */
    @Column(name = "plan_start", nullable = false)
    private LocalDateTime planStart;

    /** 计划结束时间 */
    @Column(name = "plan_end", nullable = false)
    private LocalDateTime planEnd;

    /** 检修原因 */
    @Column(name = "reason", nullable = false, length = 300)
    private String reason;

    /** 发起人 */
    @Column(name = "initiator", nullable = false, length = 30)
    private String initiator;

    /** 现场负责人 */
    @Column(name = "manager", nullable = false, length = 30)
    private String manager;

    /** 状态：PENDING / ACTIVE / RELEASED / CANCELED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 仅隔离中 = 机柜ID（唯一索引兜底并发，解除/取消后置空） */
    @Column(name = "active_cabinet")
    private Long activeCabinet;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** 解除操作人（须为现场负责人） */
    @Column(name = "release_operator", length = 30)
    private String releaseOperator;

    /** 解除说明 */
    @Column(name = "release_note", length = 300)
    private String releaseNote;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "cancel_operator", length = 30)
    private String cancelOperator;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;
}
