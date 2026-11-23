/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 单个网络接口的 /proc/net/dev 累计计数快照。
 *
 * <p>每接口单独维护基线与差分，接口新增、删除、重连或计数器重置时只重建该接口的基线。
 */
@Value
@Builder(toBuilder = true)
public class NetworkInterfaceStats {

    String name;

    long rxBytes;
    long rxPackets;
    long rxErrors;
    long rxDrops;

    long txBytes;
    long txPackets;
    long txErrors;
    long txDrops;

    /** 采集时刻的单调时间（纳秒）。 */
    long capturedNanos;
}
