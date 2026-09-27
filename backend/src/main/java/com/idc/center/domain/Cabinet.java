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
 * 模块一：机柜。行/列定位 + 总 U 位数，服务器按 U 位塞进机柜。
 */
@Entity
@Table(name = "idc_cabinet")
@DynamicUpdate
@Data
public class Cabinet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属机房 */
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", length = 50)
    private String name;

    /** 行号（从 1 开始） */
    @Column(name = "row_no")
    private Integer rowNo;

    /** 列号（从 1 开始） */
    @Column(name = "col_no")
    private Integer colNo;

    /** 总 U 位数，常见 42U */
    @Column(name = "total_u")
    private Integer totalU;

    /** 额定功率（kW） */
    @Column(name = "rated_power", precision = 8, scale = 2)
    private BigDecimal ratedPower;

    @Column(name = "remark", length = 200)
    private String remark;
}
