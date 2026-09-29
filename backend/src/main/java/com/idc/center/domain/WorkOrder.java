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
 * 模块三：上架/下架/迁移工单。
 * 状态流转：PENDING 待审批 → APPROVED 已批复 → WORKING 施工中 → DONE 已完成；
 * 待审批/已批复可以被驳回成 REJECTED 已驳回。
 */
@Entity
@Table(name = "idc_work_order")
@DynamicUpdate
@Data
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;

    /** 类型：ON 上架 / OFF 下架 / MOVE 迁移 */
    @Column(name = "order_type", length = 20)
    private String orderType;

    /** 关联服务器 */
    @Column(name = "server_id")
    private Long serverId;

    /** 资产编号快照（列表直接展示，不必再 JOIN） */
    @Column(name = "asset_no", length = 30)
    private String assetNo;

    /**
     * 目标机柜：上架 / 迁移 = 落地的新机柜；下架 = 下架前所在机柜（历史快照的一部分）。
     */
    @Column(name = "cabinet_id")
    private Long cabinetId;

    /** 目标起始 U 位（下架时即下架前起始 U 位） */
    @Column(name = "target_u")
    private Integer targetU;

    /**
     * 历史位置快照 · 下架 / 迁移【发起那一刻】所在机柜。
     * 工单一旦创建便不再随资产当前位置变化：资产重新上架或再次迁移后，
     * 本工单仍能查到这次作业前的原机柜。上架工单为空。
     */
    @Column(name = "src_cabinet_id")
    private Long srcCabinetId;

    /** 历史位置快照 · 下架 / 迁移发起那一刻的起始 U 位；上架工单为空 */
    @Column(name = "src_start_u")
    private Integer srcStartU;

    /** 申请人 */
    @Column(name = "applicant", length = 30)
    private String applicant;

    /** 申请时间 */
    @Column(name = "apply_time")
    private LocalDateTime applyTime;

    /** 状态：PENDING / APPROVED / WORKING / DONE / REJECTED */
    @Column(name = "status", length = 20)
    private String status;

    /** 审批人 */
    @Column(name = "approver", length = 30)
    private String approver;

    /** 施工人 */
    @Column(name = "handler", length = 30)
    private String handler;

    /** 完成时间 */
    @Column(name = "finish_time")
    private LocalDateTime finishTime;

    /** 驳回原因 */
    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "remark", length = 200)
    private String remark;
}
