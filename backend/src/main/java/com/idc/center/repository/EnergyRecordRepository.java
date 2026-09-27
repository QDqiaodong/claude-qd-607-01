package com.idc.center.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.EnergyRecord;

/**
 * 能耗记录：Query by Example（机柜 + 抄表月份区间 + 用电量区间）。
 */
@Repository
public interface EnergyRecordRepository extends JpaRepository<EnergyRecord, Long> {
}
