package com.idc.center.domain;

import java.math.BigDecimal;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块一：机房。机柜挂在机房下面（cabinet.room_id）。
 */
@Entity
@Table(name = "idc_room")
@DynamicUpdate
@Data
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 所在楼层，例如 "3F" */
    @Column(name = "floor_no", length = 10)
    private String floorNo;

    /** 机房面积（平方米） */
    @Column(name = "area_sqm", precision = 8, scale = 2)
    private BigDecimal areaSqm;

    /** 机房设计总功率（kW） */
    @Column(name = "power_capacity", precision = 8, scale = 2)
    private BigDecimal powerCapacity;

    /** 值班负责人 */
    @Column(name = "duty_person", length = 30)
    private String dutyPerson;

    @Column(name = "remark", length = 200)
    private String remark;
}
