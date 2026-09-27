package com.idc.center.domain;

import java.math.BigDecimal;
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
 * 模块四：巡检记录。温度/湿度/电源 + 异常描述 + 是否处理。
 * abnormal 由服务层按阈值自动判定（温度≥27℃、湿度不在 40~60%、电源异常）。
 */
@Entity
@Table(name = "idc_inspection")
@DynamicUpdate
@Data
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cabinet_id")
    private Long cabinetId;

    /** 巡检人 */
    @Column(name = "inspector", length = 30)
    private String inspector;

    /** 巡检时间 */
    @Column(name = "inspect_time")
    private LocalDateTime inspectTime;

    /** 温度（℃） */
    @Column(name = "temperature", precision = 5, scale = 2)
    private BigDecimal temperature;

    /** 湿度（%） */
    @Column(name = "humidity", precision = 5, scale = 2)
    private BigDecimal humidity;

    /** 电源状态：NORMAL 正常 / ABNORMAL 异常 */
    @Column(name = "power_status", length = 20)
    private String powerStatus;

    /** 是否异常：1 是 / 0 否 */
    @Column(name = "abnormal")
    private Integer abnormal;

    /** 异常描述 */
    @Column(name = "description", length = 300)
    private String description;

    /** 是否处理：1 是 / 0 否 */
    @Column(name = "handled")
    private Integer handled;

    /** 处理说明 */
    @Column(name = "handle_note", length = 200)
    private String handleNote;

    /** 处理时间 */
    @Column(name = "handle_time")
    private LocalDateTime handleTime;
}
