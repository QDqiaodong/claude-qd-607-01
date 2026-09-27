package com.idc.center.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import com.idc.center.dto.Views;

/**
 * Query by Example 的小工具。
 *
 * Example 只能表达「等值 / contains / 前缀」这类条件，表达不了大于小于，
 * 所以这里的做法是：先用 Example + ExampleMatcher 把能表达的筛掉（这步在 SQL 里做），
 * 再把范围条件在结果集上过一遍（演示数据量小），最后手工分页。
 */
public final class ExamplePage {

    private ExamplePage() {
    }

    /** 全表按主键倒序取（Example 为空探针 = 匹配全部） */
    public static <T> List<T> allDesc(JpaRepository<T, Long> repository, Example<T> example, String idField) {
        return repository.findAll(example, Pageable.unpaged(Sort.by(Sort.Direction.DESC, idField))).getContent();
    }

    /** 范围条件过滤 + 手工分页 */
    public static <T> Views.PageResult<T> page(List<T> rows, Predicate<T> rangeFilter, int page, int size) {
        List<T> hit = rows.stream().filter(rangeFilter).toList();
        int total = hit.size();
        if (total == 0) {
            return Views.PageResult.of(List.of(), 0, page, size);
        }
        int from = Math.min(Math.max(page, 0) * Math.max(size, 1), total);
        int to = Math.min(from + Math.max(size, 1), total);
        return Views.PageResult.of(hit.subList(from, to), total, page, size);
    }

    /** 不带范围条件时的分页（Example 已经把活干完） */
    public static <T> Views.PageResult<T> page(Page<T> pageResult) {
        Pageable pageable = pageResult.getPageable();
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : pageResult.getNumberOfElements();
        return Views.PageResult.of(pageResult.getContent(), pageResult.getTotalElements(), page, size);
    }

    /** 空字符串按「不参与筛选」处理，否则 QBE 会把空串当成等值条件 */
    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 日期区间判断：起止都可为空 */
    public static <T extends Comparable<? super T>> boolean inRange(T value, T from, T to) {
        if (value == null) {
            return false;
        }
        if (from != null && value.compareTo(from) < 0) {
            return false;
        }
        return to == null || value.compareTo(to) <= 0;
    }

    /** 数值区间判断 */
    public static boolean between(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) {
            return false;
        }
        if (min != null && value.compareTo(min) < 0) {
            return false;
        }
        return max == null || value.compareTo(max) <= 0;
    }
}
