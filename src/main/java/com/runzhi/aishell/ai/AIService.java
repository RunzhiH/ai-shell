/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

/**
 * AI服务接口，用于解耦UI层和AI适配器
 */
public interface AIService {
    
    /**
     * 发送查询到AI
     */
    String sendQuery(String query);
    
    /**
     * 检查AI服务是否已配置
     */
    boolean isConfigured();
    
    /**
     * 获取AI服务名称
     */
    String getName();
    
    /**
     * 切换到指定的AI配置
     */
    void switchToConfig(String configName);
    
    /**
     * 获取当前AI配置
     */
    AIConfig getCurrentConfig();
    
    /**
     * 更新AI适配器
     */
    void updateAdapter(AIConfig config);
    
    /**
     * 获取AI服务的状态信息
     */
    java.util.Map<String, Object> getServiceStatus();
    
    /**
     * 测试AI服务连接
     */
    boolean testConnection();
    
    /**
     * 重新初始化AI适配器
     */
    void reinitializeAdapter();
    
    /**
     * 处理命令请求
     */
    String processCommand(String command);
    
    /**
     * 获取 AI 适配器
     * @return AI 适配器实例
     */
    AIAdapter getAIAdapter();
}