/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 一轮监控采样的完整快照。
 *
 * <p>快照携带连接会话标识（sessionId），界面切换主机后旧会话返回的数据必须被丢弃；
 * 每个数据区独立携带自己的采样状态，单项失败只影响该项。
 */
@Value
@Builder(toBuilder = true)
public class MonitorSnapshot {

    /** 连接级监控会话标识，界面据此丢弃过期会话的数据。 */
    String sessionId;

    /** 采样时间（墙上时间，毫秒）。 */
    long epochMs;
    /** 本轮采样耗时（毫秒），用于评估高延迟连接的误差。 */
    long durationMs;

    /** 兼容旧界面与侧栏的系统指标。 */
    SystemMetrics systemMetrics;

    /** CPU 是否为首次差分采样（此时上方CPU% 不可用）。 */
    boolean cpuFirstSample;

    /** 文件系统容量（df），低频采集。 */
    MetricStatus<List<MountInfo>> mounts;

    /** 按设备名索引的磁盘 I/O 速率。 */
    MetricStatus<Map<String, DiskIoRate>> diskIo;

    /** 按接口名索引的网络速率。 */
    MetricStatus<Map<String, NetworkInterfaceRate>> networkRates;

    /** 按接口名索引的网络接口信息（低频采集）。 */
    MetricStatus<Map<String, NetworkInterfaceInfo>> networkInfo;

    /** 进程列表。 */
    MetricStatus<List<ProcessInfo>> processes;

    public boolean isForSession(String expectedSessionId) {
        return sessionId != null && sessionId.equals(expectedSessionId);
    }
}
