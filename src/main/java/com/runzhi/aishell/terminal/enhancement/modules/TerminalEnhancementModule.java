/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import org.fxmisc.richtext.InlineCssTextArea;

/**
 * 终端增强功能模块接口
 * 定义所有终端增强功能模块的基本契约
 */
public interface TerminalEnhancementModule {
    
    /**
     * 初始化模块
     */
    void initialize();
    
    /**
     * 启用模块功能
     * @param outputArea 终端输出区域
     * @param tabId 标签页ID
     */
    void enable(InlineCssTextArea outputArea, String tabId);
    
    /**
     * 启用模块功能（简化版，适用于不需要tabId的模块）
     * @param outputArea 终端输出区域
     */
    default void enable(InlineCssTextArea outputArea) {
        enable(outputArea, null);
    }
    
    /**
     * 禁用模块功能
     * @param outputArea 终端输出区域
     * @param tabId 标签页ID
     */
    void disable(InlineCssTextArea outputArea, String tabId);
    
    /**
     * 禁用模块功能（简化版）
     * @param outputArea 终端输出区域
     */
    default void disable(InlineCssTextArea outputArea) {
        disable(outputArea, null);
    }
    
    /**
     * 检查模块是否已启用
     * @param tabId 标签页ID
     * @return 如果模块已启用则返回true，否则返回false
     */
    boolean isEnabled(String tabId);
    
    /**
     * 模块名称
     * @return 模块的唯一标识名称
     */
    String getModuleName();
    
    /**
     * 模块描述
     * @return 模块的功能描述
     */
    String getDescription();
}