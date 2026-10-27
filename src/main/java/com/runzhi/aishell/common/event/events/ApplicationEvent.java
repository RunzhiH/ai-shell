/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.event.events;

import lombok.Getter;

import java.time.Instant;

/**
 * 应用事件基类
 * 
 * <p>所有事件都应该继承此类，提供时间戳和事件类型信息。
 */
@Getter
public abstract class ApplicationEvent {

    /** 事件创建时间 */
    private final Instant timestamp;
    
    /** 事件来源（可选） */
    private final Object source;

    protected ApplicationEvent() {
        this(null);
    }

    protected ApplicationEvent(Object source) {
        this.timestamp = Instant.now();
        this.source = source;
    }

    /**
     * 获取事件类型名称
     *
     * @return 事件类型名称
     */
    public String getEventTypeName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return String.format("%s{timestamp=%s, source=%s}", 
                getEventTypeName(), timestamp, source != null ? source.getClass().getSimpleName() : "null");
    }
}
