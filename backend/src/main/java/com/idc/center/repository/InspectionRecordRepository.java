package com.idc.center.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.InspectionRecord;

/**
 * 巡检记录：Query by Example（巡检人 contains 忽略大小写 + 机柜/处理状态精确匹配）。
 */
@Repository
public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {
}
