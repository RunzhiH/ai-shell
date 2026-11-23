/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 文件系统容量信息（来自 df），与块设备 I/O 分开表达。
 */
@Value
@Builder(toBuilder = true)
public class MountInfo {

    String device;
    String mountPoint;
    /** 容量，KiB。 */
    long totalKiB;
    long usedKiB;
    long availableKiB;
    /** 使用率百分比（0-100）。 */
    double usagePercent;
}
