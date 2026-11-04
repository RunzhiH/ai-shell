/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 终端标签页稳定身份来源。
 *
 * <p>此前 tabId 由「当前标签数 + 1」拼成（tab1、tab2…），关闭任意中间标签后再开新标签就会与存量标签撞号，
 * 导致按 tabId 索引的 SSH 连接、监控、文件管理器、Agent 审批全部张冠李戴。
 * 本类用进程级单调计数器保证同一窗口生命周期内 id 永不重复，且不复用已关闭标签的序号。
 * 跨进程（重启）不需要稳定：标签本身不跨重启存活。
 */
public final class TabIdentity {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private TabIdentity() {
    }

    /**
     * 生成一个新的标签页 id，如 "tab-3"。
     *
     * @return 全局唯一的标签页 id
     */
    public static String nextTabId() {
        return "tab-" + SEQUENCE.incrementAndGet();
    }

    /**
     * 带前缀的 id 生成，供旧入口（本地终端、SSH 直连）保留命名习惯时使用。
     *
     * @param prefix 前缀，如 "local"、"ssh"
     * @return 形如 "ssh-12" 的全局唯一 id
     */
    public static String nextId(String prefix) {
        String safe = (prefix == null || prefix.isBlank()) ? "tab" : prefix.trim();
        return safe + "-" + SEQUENCE.incrementAndGet();
    }

    /** 仅用于测试：重置计数器。 */
    static void resetForTest() {
        SEQUENCE.set(0);
    }
}
