/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 单个网络接口的差分速率结果。内部保存数值与明确单位，由界面格式化为 KiB/s、MiB/s 等，
 * 不从展示字符串反向解析。
 */
@Value
@Builder(toBuilder = true)
public class NetworkInterfaceRate {

    String name;

    /** 接收速率（字节/秒）。 */
    double rxBytesPerSec;
    /** 发送速率（字节/秒）。 */
    double txBytesPerSec;
    /** 接收包速率（包/秒）。 */
    double rxPacketsPerSec;
    /** 发送包速率（包/秒）。 */
    double txPacketsPerSec;

    /** 本次采样窗口内的接收错误增量。 */
    long rxErrorDelta;
    /** 本次采样窗口内的接收丢包增量。 */
    long rxDropDelta;
    /** 本次采样窗口内的发送错误增量。 */
    long txErrorDelta;
    /** 本次采样窗口内的发送丢包增量。 */
    long txDropDelta;

    long totalRxBytes;
    long totalTxBytes;

    SampleState state;
    String reason;
}
