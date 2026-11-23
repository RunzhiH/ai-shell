/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

import java.util.function.Supplier;

/**
 * 带状态的指标值：把「数值」与「采样状态」绑定在一起，避免界面把
 * 首次采样、失败、降级与真实零值混为一谈。
 *
 * @param <T> 指标负载类型
 */
@Value
@Builder(toBuilder = true)
public class MetricStatus<T> {

    /** 指标值；state 为 NO_DATA/FAILED 时通常为 null。 */
    T value;

    /** 采样状态。 */
    SampleState state;

    /** 状态原因（能力不足、命令不存在等），供界面与日志说明。 */
    String reason;

    /** 上一次成功采集的墙上时间（毫秒），用于判断过期。 */
    long lastSuccessEpochMs;

    public static <T> MetricStatus<T> ok(T value) {
        return MetricStatus.<T>builder()
                .value(value)
                .state(SampleState.OK)
                .lastSuccessEpochMs(System.currentTimeMillis())
                .build();
    }

    public static <T> MetricStatus<T> firstSample(T value) {
        return MetricStatus.<T>builder()
                .value(value)
                .state(SampleState.FIRST_SAMPLE)
                .lastSuccessEpochMs(System.currentTimeMillis())
                .build();
    }

    /** 采集失败但保留旧值。 */
    public static <T> MetricStatus<T> stale(T lastValue, String reason) {
        return MetricStatus.<T>builder()
                .value(lastValue)
                .state(SampleState.STALE)
                .reason(reason)
                .build();
    }

    public static <T> MetricStatus<T> failed(String reason) {
        return MetricStatus.<T>builder()
                .state(SampleState.FAILED)
                .reason(reason)
                .build();
    }

    public static <T> MetricStatus<T> degraded(T partialValue, String reason) {
        return MetricStatus.<T>builder()
                .value(partialValue)
                .state(SampleState.DEGRADED)
                .reason(reason)
                .lastSuccessEpochMs(System.currentTimeMillis())
                .build();
    }

    public static <T> MetricStatus<T> noData() {
        return MetricStatus.<T>builder().state(SampleState.NO_DATA).build();
    }

    /** 数据是否可用（含合法零值与已过期的保留值）。 */
    public boolean isUsable() {
        return state != null && state.isUsable();
    }

    /** 是否携带值。 */
    public boolean hasValue() {
        return value != null && isUsable();
    }

    /** 状态 newer 语义：把当前失败结果与上次状态合并为「已过期」。 */
    public MetricStatus<T> toStaleOrUpdate(SampleState failedState, Supplier<String> reason) {
        if (state == SampleState.OK || state == SampleState.FIRST_SAMPLE || state == SampleState.DEGRADED) {
            return MetricStatus.<T>builder()
                    .value(value)
                    .state(SampleState.STALE)
                    .reason(reason.get())
                    .lastSuccessEpochMs(lastSuccessEpochMs)
                    .build();
        }
        if (state == SampleState.STALE) {
            return this;
        }
        return MetricStatus.<T>builder()
                .state(failedState)
                .reason(reason.get())
                .build();
    }
}
