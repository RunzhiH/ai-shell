/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

/**
 * 连接状态监听器接口，用于监听SSH连接状态变化
 */
public interface ConnectionStateListener {
    /**
     * 连接建立时调用
     * @param tabId 标签页ID
     */
    void onConnected(String tabId);

    /**
     * 连接断开时调用
     * @param tabId 标签页ID
     */
    void onDisconnected(String tabId);
}