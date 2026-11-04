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

    /**
     * 该适配器当前配置的模型输出 token 上限。
     * <p>用于 Agent 在响应被 token 上限截断时判断是否值得用更大预算重试；
     * 未知（无法确定）时返回 -1，此时不提升预算。</p>
     */
    default int maxOutputTokens() { return -1; }

    /**
     * 按指定输出预算查询模型；支持该能力的适配器应把 maxOutputTokensHint 临时应用到请求。
     * <p>默认实现忽略 hint，沿用适配器自身配置。Agent 的截断重试只调用此重载。</p>
     */
    default String sendQuery(String query, int maxOutputTokensHint) { return sendQuery(query); }
    
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