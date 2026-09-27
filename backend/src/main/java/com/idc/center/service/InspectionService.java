package com.idc.center.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.stereotype.Service;

import com.idc.center.common.BizException;
import com.idc.center.domain.InspectionRecord;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.InspectionRecordRepository;

/**
 * 模块四：巡检记录。温度 / 湿度 / 电源 + 异常描述 + 是否处理。
 */
@Service
public class InspectionService {

    private final InspectionRecordRepository inspectionRepository;
    private final CabinetRepository cabinetRepository;

    public InspectionService(InspectionRecordRepository inspectionRepository, CabinetRepository cabinetRepository) {
        this.inspectionRepository = inspectionRepository;
        this.cabinetRepository = cabinetRepository;
    }

    /**
     * 查询：巡检人 contains 忽略大小写，机柜 / 电源状态 / 是否异常 / 是否处理精确匹配（Example）；
     * 温湿度区间与巡检时间区间是范围条件，在 Example 结果上再过一道。
     */
    public Views.PageResult<InspectionRecord> search(Dtos.InspectionQuery query) {
        InspectionRecord probe = new InspectionRecord();
        probe.setInspector(ExamplePage.blankToNull(query.getInspector()));
        probe.setPowerStatus(ExamplePage.blankToNull(query.getPowerStatus()));
        probe.setAbnormal(query.getAbnormal());
        probe.setHandled(query.getHandled());
        probe.setCabinetId(query.getCabinetId());

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING)
                .withMatcher("powerStatus", ExampleMatcher.GenericPropertyMatchers.exact());

        List<InspectionRecord> rows = ExamplePage.allDesc(inspectionRepository, Example.of(probe, matcher), "id");
        return ExamplePage.page(rows, matchRange(query), query.getPage(), query.getSize());
    }

    private Predicate<InspectionRecord> matchRange(Dtos.InspectionQuery query) {
        return row -> {
            if (query.getMinTemp() != null || query.getMaxTemp() != null) {
                if (!ExamplePage.between(row.getTemperature(), query.getMinTemp(), query.getMaxTemp())) {
                    return false;
                }
            }
            if (query.getMinHumidity() != null || query.getMaxHumidity() != null) {
                if (!ExamplePage.between(row.getHumidity(), query.getMinHumidity(), query.getMaxHumidity())) {
                    return false;
                }
            }
            return query.getFrom() == null && query.getTo() == null
                    || ExamplePage.inRange(row.getInspectTime(), query.getFrom(), query.getTo());
        };
    }

    public InspectionRecord create(Dtos.InspectionCreateReq req) {
        cabinetRepository.findById(req.getCabinetId()).orElseThrow(() -> new BizException("机柜不存在"));
        InspectionRecord record = new InspectionRecord();
        record.setCabinetId(req.getCabinetId());
        record.setInspector(req.getInspector().trim());
        record.setInspectTime(req.getInspectTime() == null ? LocalDateTime.now() : req.getInspectTime());
        record.setTemperature(req.getTemperature());
        record.setHumidity(req.getHumidity());
        record.setPowerStatus(req.getPowerStatus() == null ? "NORMAL" : req.getPowerStatus());
        record.setDescription(req.getDescription());
        boolean abnormal = IdcRule.isAbnormal(req.getTemperature(), req.getHumidity(), record.getPowerStatus());
        record.setAbnormal(abnormal ? 1 : 0);
        record.setHandled(abnormal ? 0 : 1);
        return inspectionRepository.save(record);
    }

    /** 异常闭环：登记处理说明 */
    public InspectionRecord handle(Long id, Dtos.HandleReq req) {
        InspectionRecord record = inspectionRepository.findById(id).orElseThrow(() -> new BizException("巡检记录不存在"));
        if (Integer.valueOf(1).equals(record.getHandled())) {
            throw new BizException("该巡检记录已处理");
        }
        record.setHandled(1);
        record.setHandleNote(req.getNote().trim());
        record.setHandleTime(LocalDateTime.now());
        return inspectionRepository.save(record);
    }

    /** 顶部汇总：总条数 / 异常数 / 未处理异常数 */
    public Views.InspectionSummary summary() {
        List<InspectionRecord> all = inspectionRepository.findAll(
                Example.of(new InspectionRecord(), ExampleMatcher.matchingAll().withIgnoreNullValues()));
        Views.InspectionSummary summary = new Views.InspectionSummary();
        summary.setTotal((long) all.size());
        summary.setAbnormal(all.stream().filter(row -> Integer.valueOf(1).equals(row.getAbnormal())).count());
        summary.setUnhandled(all.stream()
                .filter(row -> Integer.valueOf(1).equals(row.getAbnormal()))
                .filter(row -> !Integer.valueOf(1).equals(row.getHandled()))
                .count());
        return summary;
    }
}
