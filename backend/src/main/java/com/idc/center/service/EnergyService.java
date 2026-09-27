package com.idc.center.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.idc.center.common.BizException;
import com.idc.center.domain.EnergyRecord;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.EnergyRecordRepository;

/**
 * 模块五：能耗记录。抄表读数单调递增，用电量 = 本次读数 − 上次读数，电费 = 用电量 × 电价。
 */
@Service
public class EnergyService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final EnergyRecordRepository energyRepository;
    private final CabinetRepository cabinetRepository;

    public EnergyService(EnergyRecordRepository energyRepository, CabinetRepository cabinetRepository) {
        this.energyRepository = energyRepository;
        this.cabinetRepository = cabinetRepository;
    }

    /**
     * 查询：机柜精确匹配 + 抄表人 contains 忽略大小写（Example）；
     * 月份与用电量区间是范围条件，在 Example 结果上再过一道。
     */
    public Views.PageResult<EnergyRecord> search(Dtos.EnergyQuery query) {
        EnergyRecord probe = new EnergyRecord();
        probe.setCabinetId(query.getCabinetId());
        probe.setMeterReader(ExamplePage.blankToNull(query.getMeterReader()));

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING);

        List<EnergyRecord> rows = ExamplePage.allDesc(energyRepository, Example.of(probe, matcher), "id");
        return ExamplePage.page(rows, matchRange(query), query.getPage(), query.getSize());
    }

    private Predicate<EnergyRecord> matchRange(Dtos.EnergyQuery query) {
        LocalDate[] range = monthRange(query.getMonth());
        return row -> {
            if (range[0] != null && !ExamplePage.inRange(row.getMeterDate(), range[0], range[1])) {
                return false;
            }
            if (query.getMinUsage() != null || query.getMaxUsage() != null) {
                return ExamplePage.between(row.getUsageKwh(), query.getMinUsage(), query.getMaxUsage());
            }
            return true;
        };
    }

    public EnergyRecord create(Dtos.EnergyCreateReq req) {
        cabinetRepository.findById(req.getCabinetId()).orElseThrow(() -> new BizException("机柜不存在"));
        BigDecimal reading = req.getMeterReading();
        BigDecimal price = req.getUnitPrice() == null ? IdcRule.DEFAULT_PRICE : req.getUnitPrice();

        EnergyRecord cabinetProbe = new EnergyRecord();
        cabinetProbe.setCabinetId(req.getCabinetId());
        EnergyRecord previous = energyRepository.findAll(
                        Example.of(cabinetProbe, ExampleMatcher.matchingAll().withIgnoreNullValues()),
                        Pageable.unpaged())
                .getContent().stream()
                .filter(row -> row.getMeterDate() != null && row.getMeterDate().isBefore(req.getMeterDate()))
                .max(Comparator.comparing(EnergyRecord::getMeterDate))
                .orElse(null);

        if (previous != null && reading.compareTo(previous.getMeterReading()) < 0) {
            throw new BizException("电表读数不能小于上一次抄表读数（" + previous.getMeterReading() + " kWh）");
        }

        EnergyRecord record = new EnergyRecord();
        record.setCabinetId(req.getCabinetId());
        record.setMeterDate(req.getMeterDate());
        record.setMeterReading(reading);
        record.setUnitPrice(price);
        record.setMeterReader(req.getMeterReader());
        record.setRemark(req.getRemark());
        BigDecimal usage = previous == null ? BigDecimal.ZERO : reading.subtract(previous.getMeterReading());
        record.setUsageKwh(usage.setScale(2, RoundingMode.HALF_UP));
        record.setFee(usage.multiply(price).setScale(2, RoundingMode.HALF_UP));
        return energyRepository.save(record);
    }

    /** 本月（或指定月份）总用电 / 总电费 + 按机柜用电排行 */
    public Views.EnergySummary summary(String month) {
        LocalDate[] range = monthRange(month);
        List<EnergyRecord> rows = energyRepository.findAll(
                Example.of(new EnergyRecord(), ExampleMatcher.matchingAll().withIgnoreNullValues()));

        BigDecimal totalUsage = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        Map<Long, BigDecimal> usageByCabinet = new HashMap<>();
        Map<Long, BigDecimal> feeByCabinet = new HashMap<>();
        int count = 0;
        for (EnergyRecord row : rows) {
            if (range[0] != null && !ExamplePage.inRange(row.getMeterDate(), range[0], range[1])) {
                continue;
            }
            count += 1;
            BigDecimal usage = row.getUsageKwh() == null ? BigDecimal.ZERO : row.getUsageKwh();
            BigDecimal fee = row.getFee() == null ? BigDecimal.ZERO : row.getFee();
            totalUsage = totalUsage.add(usage);
            totalFee = totalFee.add(fee);
            usageByCabinet.merge(row.getCabinetId(), usage, BigDecimal::add);
            feeByCabinet.merge(row.getCabinetId(), fee, BigDecimal::add);
        }

        List<Views.CabinetUsageRow> ranking = new ArrayList<>();
        usageByCabinet.forEach((cabinetId, usage) -> {
            Views.CabinetUsageRow row = new Views.CabinetUsageRow();
            row.setCabinetId(cabinetId);
            row.setCabinetCode(cabinetRepository.findById(cabinetId).map(cabinet -> cabinet.getCode()).orElse("—"));
            row.setUsageKwh(usage.setScale(2, RoundingMode.HALF_UP));
            row.setFee(feeByCabinet.getOrDefault(cabinetId, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            ranking.add(row);
        });
        ranking.sort(Comparator.comparing(Views.CabinetUsageRow::getUsageKwh).reversed());

        Views.EnergySummary summary = new Views.EnergySummary();
        summary.setMonth(month == null || month.isBlank()
                ? LocalDate.now().format(FORMATTER) : month);
        summary.setTotalUsage(totalUsage.setScale(2, RoundingMode.HALF_UP));
        summary.setTotalFee(totalFee.setScale(2, RoundingMode.HALF_UP));
        summary.setRecordCount(count);
        summary.setCabinetCount(usageByCabinet.size());
        summary.setRanking(ranking.stream().limit(8).toList());
        return summary;
    }

    /** "yyyy-MM" → [月初, 月末]；空则取当前月 */
    private LocalDate[] monthRange(String month) {
        String value = month == null || month.isBlank()
                ? LocalDate.now().format(FORMATTER) : month.trim();
        int year;
        int mon;
        try {
            year = Integer.parseInt(value.substring(0, 4));
            mon = Integer.parseInt(value.substring(5, 7));
        } catch (RuntimeException e) {
            throw new BizException("月份格式应为 yyyy-MM");
        }
        LocalDate from = LocalDate.of(year, mon, 1);
        return new LocalDate[]{from, from.withDayOfMonth(from.lengthOfMonth())};
    }
}
