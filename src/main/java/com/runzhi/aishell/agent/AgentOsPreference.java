/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.runzhi.aishell.common.config.ConfigManager;

import java.util.List;
import java.util.Locale;

/**
 * Agent 目标服务器操作系统偏好：技能库过滤与 Agent 系统提示词都读取它。
 * <p>
 * 存储于 ~/.aishell/app_configs.json（文件型，不依赖注册表），默认 linux；
 * AI 设置页的操作系统下拉框写入此偏好（此前该下拉框没有任何消费方）。
 */
public final class AgentOsPreference {

    public static final String DEFAULT_OS = "linux";
    public static final String DEFAULT_SHELL = "bash";
    public static final String ALL = "all";
    /** 设置页下拉框与 /skills 过滤参数支持的取值。 */
    public static final List<String> SUPPORTED = List.of("linux", "macos", "windows", ALL);
    /** 设置页 Shell 下拉框支持的取值。 */
    public static final List<String> SUPPORTED_SHELLS = List.of("bash", "zsh", "powershell", "cmd");

    private static final String KEY = "agent.targetOs";
    private static final String SHELL_KEY = "agent.targetShell";

    private AgentOsPreference() {
        // 工具类，禁止实例化
    }

    /** 当前选中的目标操作系统（小写；未配置或非法时回落 linux）。 */
    public static String current() {
        try {
            Object value = ConfigManager.getInstance().loadAppConfig().get(KEY);
            if (value instanceof String text) {
                return normalize(text);
            }
        } catch (Exception ignored) {
            // 配置读取失败回落默认值，不得抛到调用方
        }
        return DEFAULT_OS;
    }

    /** 写入目标操作系统偏好（非法值忽略）。 */
    public static void set(String os) {
        String normalized = normalize(os);
        if (!SUPPORTED.contains(normalized)) {
            return;
        }
        ConfigManager.getInstance().saveAppConfig(KEY, normalized);
    }

    /** 当前选中的目标 Shell（小写；未配置或非法时回落 bash）。 */
    public static String currentShell() {
        try {
            Object value = ConfigManager.getInstance().loadAppConfig().get(SHELL_KEY);
            if (value instanceof String text) {
                return normalizeShell(text);
            }
        } catch (Exception ignored) {
            // 配置读取失败回落默认值，不得抛到调用方
        }
        return DEFAULT_SHELL;
    }

    /** 写入目标 Shell 偏好（非法值忽略）。 */
    public static void setShell(String shell) {
        String normalized = normalizeShell(shell);
        if (!SUPPORTED_SHELLS.contains(normalized)) {
            return;
        }
        ConfigManager.getInstance().saveAppConfig(SHELL_KEY, normalized);
    }

    private static String normalize(String os) {
        return os == null || os.isBlank() ? DEFAULT_OS : os.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeShell(String shell) {
        if (shell == null || shell.isBlank()) return DEFAULT_SHELL;
        String value = shell.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "zsh", "powershell", "cmd" -> value;
            case "powershell.exe", "pwsh" -> "powershell";
            default -> "bash";
        };
    }
}
