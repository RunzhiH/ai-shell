/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import java.util.Map;

public interface AIAdapter {
    String sendQuery(String query);

    boolean isConfigured();

    String getName();
    
    // 生成命令的方法
    String generateCommand(String naturalLanguage);
    
    // 解释命令的方法
    String explainCommand(String command);
    
    // 获取帮助的方法
    String getHelp(String topic);
    
    // 添加通用配置方法
    default void setConfig(Map<String, Object> config) {
        // 默认空实现
    }
}