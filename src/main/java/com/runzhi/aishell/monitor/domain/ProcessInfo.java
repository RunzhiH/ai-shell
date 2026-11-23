/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 单条进程信息。
 *
 * <p>进程身份采用「连接会话 + PID + 启动标识」：identityKey 由 PID 与启动时间组成，
 * 用于避免 PID 复用导致的误识别。CPU% 采用单核满载 100% 的口径，允许多线程进程超过 100%。
 */
@Value
@Builder(toBuilder = true)
public class ProcessInfo {

    int pid;
    int ppid;
    String user;

    /** CPU 使用率（单核满载 100%）。 */
    double cpuPercent;
    /** 内存使用率百分比。 */
    double memPercent;
    /** 常驻内存（KiB）。 */
    long rssKiB;

    /** 进程状态（R/S/D/T/Z/I 及修饰位）。 */
    String state;
    int threadCount;

    /** 启动时间（ps lstart 原始串）。 */
    String startedAt;
    /** 运行时长（ps etime 串）。 */
    String elapsed;

    /** 进程名（命令首个分量的 basename）。 */
    String name;
    /** 完整命令行（可能含空格与特殊字符）。 */
    String commandLine;

    /** 身份键：PID + 启动标识，用于跨采样稳定匹配。 */
    String identityKey;

    /** 与上次采样相比的常驻内存变化（KiB）。 */
    long rssDeltaKiB;
    /** 与上次采样相比的 CPU 使用率变化。 */
    double cpuDelta;
}
