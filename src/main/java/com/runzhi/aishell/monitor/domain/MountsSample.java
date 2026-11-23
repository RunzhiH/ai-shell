/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 文件系统容量采样结果（低频通道）。
 */
@Value
@Builder(toBuilder = true)
public class MountsSample {

    List<MountInfo> mounts;
    /** 最大使用率百分比。 */
    double maxUsagePercent;
    boolean available;
    String failureReason;

    public static MountsSample ok(List<MountInfo> mounts, double maxUsagePercent) {
        return MountsSample.builder()
                .mounts(mounts)
                .maxUsagePercent(maxUsagePercent)
                .available(true)
                .build();
    }

    public static MountsSample empty(String reason) {
        return MountsSample.builder()
                .mounts(java.util.List.of())
                .available(false)
                .failureReason(reason)
                .build();
    }
}
