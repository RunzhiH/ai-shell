/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement;

import com.runzhi.aishell.common.ai.AIEnhancementUtil;
import com.runzhi.aishell.terminal.enhancement.modules.*;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.HashMap;
import java.util.Map;

/**
 * 终端增强功能管理器 - 改进版本
 * 集中管理所有终端增强功能，提供统一的API接口
 * 采用模块化设计，便于扩展和维护
 */
public class TerminalEnhancementManager {

    private static volatile TerminalEnhancementManager instance;
    private final Map<String, TerminalEnhancementModule> enhancementModules = new HashMap<>();

    // 增强功能模块
    private final EnhancedCommandCompletionModule enhancedCommandCompletionModule;
    private final SyntaxHighlightingModule syntaxHighlightingModule;
    private final EnhancedHistoryManagementModule enhancedHistoryManagementModule;
    private final EnhancedThemeModule enhancedThemeModule;
    private final EnhancedKeyboardShortcutModule enhancedKeyboardShortcutModule;
    private final EnhancedAIModule enhancedAIModule;

    private TerminalEnhancementManager() {
        this.enhancedCommandCompletionModule = new EnhancedCommandCompletionModule();
        this.syntaxHighlightingModule = new SyntaxHighlightingModule();
        this.enhancedHistoryManagementModule = new EnhancedHistoryManagementModule();
        this.enhancedThemeModule = new EnhancedThemeModule();
        this.enhancedKeyboardShortcutModule = new EnhancedKeyboardShortcutModule();
        this.enhancedAIModule = new EnhancedAIModule();

        // 注册模块
        enhancementModules.put("enhanced-command-completion", enhancedCommandCompletionModule);
        enhancementModules.put("syntaxHighlighting", syntaxHighlightingModule);
        enhancementModules.put("enhanced-history-management", enhancedHistoryManagementModule);
        enhancementModules.put("enhanced-theme", enhancedThemeModule);
        enhancementModules.put("enhanced-keyboard-shortcut", enhancedKeyboardShortcutModule);
        enhancementModules.put("enhanced-ai", enhancedAIModule);
        
        // 初始化所有模块
        initializeModules();
    }

    public static synchronized TerminalEnhancementManager getInstance() {
        if (instance == null) {
            instance = new TerminalEnhancementManager();
        }
        return instance;
    }

    /**
     * 初始化所有模块
     */
    private void initializeModules() {
        for (TerminalEnhancementModule module : enhancementModules.values()) {
            module.initialize();
        }
    }

    public EnhancedThemeModule getEnhancedThemeModule() {
        return enhancedThemeModule;
    }

    public EnhancedCommandCompletionModule getEnhancedCommandCompletionModule() {
        return enhancedCommandCompletionModule;
    }

    public SyntaxHighlightingModule getSyntaxHighlightingModule() {
        return syntaxHighlightingModule;
    }

    public EnhancedHistoryManagementModule getEnhancedHistoryManagementModule() {
        return enhancedHistoryManagementModule;
    }

    public EnhancedKeyboardShortcutModule getEnhancedKeyboardShortcutModule() {
        return enhancedKeyboardShortcutModule;
    }
    
    public EnhancedAIModule getEnhancedAIModule() {
        return enhancedAIModule;
    }

    /**
     * 获取AI增强工具实例
     */
    public AIEnhancementUtil getAIEnhancementUtil() {
        return AIEnhancementUtil.getInstance();
    }

    /**
     * 为终端输出区域启用所有增强功能
     */
    public void enableAllEnhancements(InlineCssTextArea outputArea, String tabId) {
        // 启用增强命令补全
        enhancedCommandCompletionModule.enable(outputArea, tabId);

        // 启用语法高亮
        syntaxHighlightingModule.enable(outputArea);

        // 启用增强历史管理
        enhancedHistoryManagementModule.enable(outputArea, tabId);

        // 启用增强键盘快捷键
        enhancedKeyboardShortcutModule.enable(outputArea, tabId);
        
        // 启用增强AI功能
        enhancedAIModule.enable(outputArea, tabId);
    }
    
    /**
     * 为终端输出区域禁用所有增强功能
     */
    public void disableAllEnhancements(InlineCssTextArea outputArea, String tabId) {
        // 禁用增强命令补全
        enhancedCommandCompletionModule.disable(outputArea);

        // 禁用语法高亮
        syntaxHighlightingModule.disable(outputArea);

        // 禁用增强历史管理
        enhancedHistoryManagementModule.disable(outputArea, tabId);

        // 禁用增强键盘快捷键
        enhancedKeyboardShortcutModule.disable(outputArea, tabId);
        
        // 禁用增强AI功能
        enhancedAIModule.disable(outputArea, tabId);
    }

    /**
     * 获取特定增强功能模块
     */
    public TerminalEnhancementModule getEnhancementModule(String moduleName) {
        return enhancementModules.get(moduleName);
    }
    
    /**
     * 动态启用特定模块
     */
    public void enableModule(String moduleName, InlineCssTextArea outputArea, String tabId) {
        TerminalEnhancementModule module = enhancementModules.get(moduleName);
        if (module != null) {
            module.enable(outputArea, tabId);
        }
    }
    
    /**
     * 动态禁用特定模块
     */
    public void disableModule(String moduleName, InlineCssTextArea outputArea, String tabId) {
        TerminalEnhancementModule module = enhancementModules.get(moduleName);
        if (module != null) {
            module.disable(outputArea, tabId);
        }
    }
    
    /**
     * 检查模块是否已启用
     */
    public boolean isModuleEnabled(String moduleName, String tabId) {
        TerminalEnhancementModule module = enhancementModules.get(moduleName);
        return module != null && module.isEnabled(tabId);
    }
    
    /**
     * 启用键盘快捷键
     */
    public void enableKeyboardShortcuts(InlineCssTextArea outputArea, String tabId) {
        enhancedKeyboardShortcutModule.enable(outputArea, tabId);
    }
    
    /**
     * 启用AI增强功能
     */
    public void enableAIEnhancements(InlineCssTextArea outputArea, String tabId) {
        enhancedAIModule.enable(outputArea, tabId);
    }
    
    /**
     * 启用增强命令补全功能
     */
    public void enableEnhancedCommandCompletion(InlineCssTextArea outputArea, String tabId) {
        enhancedCommandCompletionModule.enable(outputArea, tabId);
    }
    
    /**
     * 启用增强历史管理功能
     */
    public void enableEnhancedHistoryManagement(InlineCssTextArea outputArea, String tabId) {
        enhancedHistoryManagementModule.enable(outputArea, tabId);
    }
    
    /**
     * 启用增强主题功能
     */
    public void enableEnhancedTheming(InlineCssTextArea outputArea, String tabId) {
        enhancedThemeModule.enable(outputArea, tabId);
    }
}