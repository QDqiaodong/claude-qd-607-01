package com.idc.center.domain;

import java.math.BigDecimal;
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
 * 模块五：能耗记录。抄表日期 + 电表读数 + 用电量 + 电价 → 电费。
 */
@Entity
@Table(name = "idc_energy")
@DynamicUpdate
@Data
public class EnergyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cabinet_id")
    private Long cabinetId;

    /** 抄表日期 */
    @Column(name = "meter_date")
    private LocalDate meterDate;

    /** 电表读数（kWh，累计值） */
    @Column(name = "meter_reading", precision = 12, scale = 2)
    private BigDecimal meterReading;

    /** 本期用电量（kWh） */
    @Column(name = "usage_kwh", precision = 12, scale = 2)
    private BigDecimal usageKwh;

    /** 电价（元/kWh） */
    @Column(name = "unit_price", precision = 6, scale = 3)
    private BigDecimal unitPrice;

    /** 电费（元）= 用电量 × 电价 */
    @Column(name = "fee", precision = 12, scale = 2)
    private BigDecimal fee;

    /** 抄表人 */
    @Column(name = "meter_reader", length = 30)
    private String meterReader;

    @Column(name = "remark", length = 200)
    private String remark;
}
