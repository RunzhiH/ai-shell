/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.ai;

import com.runzhi.aishell.ai.AIService;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.ssh.SSHService;

/**
 * 模块管理器接口 - 用于集中管理所有核心服务组件
 * 实现统一的初始化、获取和生命周期管理
 */
public interface ModuleManager {
    
    /**
     * 初始化所有模块
     */
    void initialize();
    
    /**
     * 获取SSH服务
     */
    SSHService getSshService();
    
    /**
     * 获取AI服务
     */
    AIService getAiService();
    
    /**
     * 获取系统监控服务
     */
    SystemMonitor getSystemMonitor();
    
    /**
     * 关闭所有模块
     */
    void shutdown();
    
    /**
     * 重新加载所有模块
     */
    void reload();
        
    // ==================== 以下是从 PageManager 合并的功能 ====================
}