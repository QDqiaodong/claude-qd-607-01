package com.idc.center.domain;

import java.time.LocalDate;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块二：服务器资产。状态 ONLINE 在架 / OFFLINE 下架 / REPAIR 维修 / SCRAP 报废。
 * 在架、维修、报废三种状态才在机架图上占 U 位；下架的不再占位置。
 */
@Entity
@Table(name = "idc_server")
@DynamicUpdate
@Data
public class ServerAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资产编号 */
    @Column(name = "asset_no", nullable = false, length = 30)
    private String assetNo;

    @Column(name = "brand", length = 30)
    private String brand;

    @Column(name = "model", length = 40)
    private String model;

    /** 序列号 */
    @Column(name = "serial_no", length = 60)
    private String serialNo;

    /** CPU 型号 */
    @Column(name = "cpu_model", length = 60)
    private String cpuModel;

    /** 内存（GB） */
    @Column(name = "memory_gb")
    private Integer memoryGb;

    /** 硬盘配置描述 */
    @Column(name = "disk_spec", length = 80)
    private String diskSpec;

    /** 管理 IP */
    @Column(name = "ip_address", length = 40)
    private String ipAddress;

    @Column(name = "os_version", length = 40)
    private String osVersion;

    /** 所属机柜 */
    @Column(name = "cabinet_id")
    private Long cabinetId;

    /** 起始 U 位（自下往上，从 1 开始） */
    @Column(name = "start_u")
    private Integer startU;

    /** 占用 U 数 */
    @Column(name = "size_u")
    private Integer sizeU;

    /** 状态：ONLINE / OFFLINE / REPAIR / SCRAP */
    @Column(name = "status", length = 20)
    private String status;

    /** 归属业务系统 */
    @Column(name = "owner_name", length = 50)
    private String ownerName;

    /** 上架日期 */
    @Column(name = "buy_date")
    private LocalDate buyDate;

    @Column(name = "remark", length = 200)
    private String remark;
}
