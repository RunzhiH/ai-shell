/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

/**
 * 数据源提供者接口，用于解耦系统监控模块与SSH连接管理器
 */
public interface DataSourceProvider {
    
    /**
     * 执行远程命令并返回结果
     */
    String executeCommand(String command) throws Exception;
    
    /**
     * 检查是否已连接
     */
    boolean isConnected();
}