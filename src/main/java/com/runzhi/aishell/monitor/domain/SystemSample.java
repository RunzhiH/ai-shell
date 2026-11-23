/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 系统级采集结果：CPU/内存/负载/运行时间与文件系统容量。
 */
@Value
@Builder(toBuilder = true)
public class SystemSample {

    SystemMetrics systemMetrics;

    /** CPU 是否首次差分采样（尚未有增量可用）。 */
    boolean cpuFirstSample;

    /** 文件系统容量列表（df -Pk 解析）。 */
    List<MountInfo> mounts;

    /** 容量采集是否成功。 */
    boolean mountsAvailable;
}
