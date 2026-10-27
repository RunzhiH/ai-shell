/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

/**
 * 本机系统信息（操作系统、JVM、处理器、内存概况），用于「查看系统信息」。
 */
public final class SystemInfoProvider {

    private SystemInfoProvider() {
    }

    public static String getSystemInfo() {
        StringBuilder sysInfo = new StringBuilder();
        sysInfo.append("操作系统: ").append(System.getProperty("os.name")).append("\n");
        sysInfo.append("系统架构: ").append(System.getProperty("os.arch")).append("\n");
        sysInfo.append("JVM版本: ").append(System.getProperty("java.version")).append("\n");
        sysInfo.append("可用处理器: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        sysInfo.append("最大内存: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");

        return sysInfo.toString();
    }
}
