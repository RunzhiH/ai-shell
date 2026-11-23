/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 单个块设备的差分速率结果。
 *
 * <p>速率均由相邻两次快照的实际时间差计算；平均请求耗时依赖读写操作增量，
 * 没有操作时为 null（界面显示 —）；繁忙时间占比仅作辅助判断。
 */
@Value
@Builder(toBuilder = true)
public class DiskIoRate {

    String device;

    /** 读取速率（字节/秒）。 */
    double readBytesPerSec;
    /** 写入速率（字节/秒）。 */
    double writeBytesPerSec;
    /** 读 IOPS。 */
    double readIops;
    /** 写 IOPS。 */
    double writeIops;
    /** 平均请求耗时（毫秒）；本次没有读写操作增量时为 null。 */
    Double avgRequestMs;
    /** 繁忙时间占比（0-100），辅助判断而非饱和结论。 */
    double busyPercent;

    /** 累计读取字节数。 */
    long totalReadBytes;
    /** 累计写入字节数。 */
    long totalWriteBytes;

    /** 设备层级：物理设备 / 分区 / 逻辑卷 / 虚拟设备。 */
    String deviceLevel;

    /** 关联挂载点（可能为空；允许挂载点与设备的复杂映射）。 */
    String mountPoint;

    SampleState state;
    String reason;
}
