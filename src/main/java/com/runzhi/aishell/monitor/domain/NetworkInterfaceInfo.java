/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 网络接口的低频静态信息（IP、MTU、状态、链路速率）。
 *
 * <p>链路速率无法获取时为 null，界面显示未知且不计算带宽利用率。
 */
@Value
@Builder(toBuilder = true)
public class NetworkInterfaceInfo {

    String name;

    /** 接口运行状态（UP/DOWN/UNKNOWN）。 */
    String operState;

    /** IPv4 地址（可能为空）。 */
    String ipv4Address;

    /** IPv6 地址（可能为空）。 */
    String ipv6Address;

    /** MTU。 */
    Integer mtu;

    /** 链路速率（Mbps），无法获取时为 null。 */
    Integer speedMbps;
}
