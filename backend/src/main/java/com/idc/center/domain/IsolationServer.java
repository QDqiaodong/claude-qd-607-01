package com.idc.center.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 隔离生效那一刻固定的受影响服务器快照（当时仍在架或维修中的）。
 * 快照只写一次、永不更新，后续资产变更不会改写历史事实；
 * (isolation_id, server_id) 唯一索引兜底，重试 / 重复提交不会产生重复明细。
 */
@Entity
@Table(name = "idc_isolation_server")
@Data
public class IsolationServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 隔离记录 */
    @Column(name = "isolation_id", nullable = false)
    private Long isolationId;

    /** 服务器 */
    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "asset_no", length = 30)
    private String assetNo;

    @Column(name = "brand", length = 30)
    private String brand;

    @Column(name = "model", length = 40)
    private String model;

    /** 生效时的起始 U 位 */
    @Column(name = "start_u")
    private Integer startU;

    /** 生效时的占用 U 数 */
    @Column(name = "size_u")
    private Integer sizeU;

    /** 生效时的业务归属 */
    @Column(name = "owner_name", length = 50)
    private String ownerName;

    /** 生效时状态：ONLINE 在架 / REPAIR 维修 */
    @Column(name = "status", length = 20)
    private String status;
}
