package com.idc.center.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 隔离生效那一刻被冻结的上架 / 迁移施工快照（待审批 / 已批复的工单）。
 * 只记录事实，不改写工单本身的状态；隔离期间工单能否推进由实时校验把关。
 */
@Entity
@Table(name = "idc_isolation_order")
@Data
public class IsolationOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 隔离记录 */
    @Column(name = "isolation_id", nullable = false)
    private Long isolationId;

    /** 工单 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_no", length = 30)
    private String orderNo;

    /** 工单类型快照：ON 上架 / MOVE 迁移 */
    @Column(name = "order_type", length = 20)
    private String orderType;

    @Column(name = "asset_no", length = 30)
    private String assetNo;

    /** 目标 U 位快照 */
    @Column(name = "target_u")
    private Integer targetU;

    @Column(name = "applicant", length = 30)
    private String applicant;

    /** 冻结时状态：PENDING 待审批 / APPROVED 已批复 */
    @Column(name = "status_at_freeze", length = 20)
    private String statusAtFreeze;
}
