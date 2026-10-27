/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.event.events;

import com.runzhi.aishell.ssh.domain.ConnectionParams;
import lombok.Getter;

/**
 * Tab 创建事件
 * 
 * <p>当创建新的终端 Tab 时触发此事件。
 */
@Getter
public class TabCreatedEvent extends ApplicationEvent {

    /** Tab ID */
    private final String tabId;
    
    /** 连接参数（可能为 null，如果 Tab 未立即连接） */
    private final ConnectionParams connectionParams;

    public TabCreatedEvent(String tabId) {
        this(tabId, null);
    }

    public TabCreatedEvent(String tabId, ConnectionParams connectionParams) {
        this.tabId = tabId;
        this.connectionParams = connectionParams;
    }

    @Override
    public String toString() {
        return String.format("TabCreatedEvent{tabId='%s', hasConnectionParams=%s}", 
                tabId, connectionParams != null);
    }
}
