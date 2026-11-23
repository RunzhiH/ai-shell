/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 单个块设备的 /proc/diskstats 累计计数快照。
 *
 * <p>字段与 Linux 内核文档 {@code Documentation/admin-guide/iostats.rst} 对应，
 * 扇区字节大小由 {@code /sys/block/<dev>/queue/hw_sector_size} 决定（默认 512）：
 * <pre>
 *  major minor device
 *  readsCompleted readsMerged sectorsRead timeReadingMs
 *  writesCompleted writesMerged sectorsWritten timeWritingMs
 *  iosInProgress ioTimeMs weightedIoTimeMs
 * </pre>
 */
@Value
@Builder(toBuilder = true)
public class DiskIoStats {

    String device;

    long readsCompleted;
    long readsMerged;
    long sectorsRead;
    long timeReadingMs;

    long writesCompleted;
    long writesMerged;
    long sectorsWritten;
    long timeWritingMs;

    long iosInProgress;
    long ioTimeMs;
    long weightedIoTimeMs;

    /** 采集时刻的单调时间（纳秒），与计数一起用于差分。 */
    long capturedNanos;

    /** 已完成的读写字节数（按设备扇区大小换算）。 */
    long readBytes;
    long writeBytes;

    /** 扇区字节大小（来自 sysfs，默认 512）。 */
    int sectorSize;
}
