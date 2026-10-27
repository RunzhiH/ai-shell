/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.config;

/** Supported main views that can safely be opened without connection parameters. */
public enum StartupPage {
    CONNECTIONS("连接列表"), TERMINAL("终端"), WELCOME("欢迎页");

    private final String label;
    StartupPage(String label) { this.label = label; }
    public String label() { return label; }

    public static StartupPage fromPreference(String value) {
        if (value != null) {
            for (StartupPage page : values()) {
                // 兼容历史中文标签与规范枚举名两种存储
                if (page.label.equals(value) || page.name().equalsIgnoreCase(value)) return page;
            }
        }
        return CONNECTIONS;
    }
}
