/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.enhancement;

import com.runzhi.aishell.common.ai.AIEnhancementUtil;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shell增强功能管理器
 * 集中管理所有Shell增强功能，提供统一的API接口
 */
public class ShellEnhancementManager {

    private static volatile ShellEnhancementManager instance;
    private final Map<String, Object> enhancementModules = new HashMap<>();

    // 增强功能模块
    private final CommandCompletionEnhancer commandCompletionEnhancer;
    private final SyntaxHighlightingEnhancer syntaxHighlightingEnhancer;
    private final HistoryManagementEnhancer historyManagementEnhancer;
    private final ThemeEnhancer themeEnhancer;
    private final KeyboardShortcutEnhancer keyboardShortcutEnhancer;

    private ShellEnhancementManager() {
        this.commandCompletionEnhancer = new CommandCompletionEnhancer();
        this.syntaxHighlightingEnhancer = new SyntaxHighlightingEnhancer();
        this.historyManagementEnhancer = new HistoryManagementEnhancer();
        this.themeEnhancer = new ThemeEnhancer();
        this.keyboardShortcutEnhancer = new KeyboardShortcutEnhancer();

        // 注册模块
        registerModule("commandCompletion", commandCompletionEnhancer);
        registerModule("syntaxHighlighting", syntaxHighlightingEnhancer);
        registerModule("historyManagement", historyManagementEnhancer);
        registerModule("theme", themeEnhancer);
        registerModule("keyboardShortcut", keyboardShortcutEnhancer);
    }

    public static synchronized ShellEnhancementManager getInstance() {
        if (instance == null) {
            instance = new ShellEnhancementManager();
        }
        return instance;
    }

    /**
     * 注册增强模块
     */
    private void registerModule(String name, Object module) {
        enhancementModules.put(name, module);
    }

    public ThemeEnhancer getThemeEnhancer() {
        return themeEnhancer;
    }

    public CommandCompletionEnhancer getCommandCompletionEnhancer() {
        return commandCompletionEnhancer;
    }

    public SyntaxHighlightingEnhancer getSyntaxHighlightingEnhancer() {
        return syntaxHighlightingEnhancer;
    }

    public HistoryManagementEnhancer getHistoryManagementEnhancer() {
        return historyManagementEnhancer;
    }

    public KeyboardShortcutEnhancer getKeyboardShortcutEnhancer() {
        return keyboardShortcutEnhancer;
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
        commandCompletionEnhancer.enableCommandCompletion(outputArea);
        syntaxHighlightingEnhancer.enableSyntaxHighlighting(outputArea);
        historyManagementEnhancer.enableHistoryManagement(outputArea, tabId);
        keyboardShortcutEnhancer.enableKeyboardShortcuts(outputArea, tabId);
    }

    /**
     * 为终端输出区域启用指定的增强功能
     */
    public void enableEnhancement(InlineCssTextArea outputArea, String tabId, 
                                  Consumer<InlineCssTextArea>... enhancers) {
        for (Consumer<InlineCssTextArea> enhancer : enhancers) {
            try {
                enhancer.accept(outputArea);
            } catch (Exception e) {
                System.err.println("Error applying enhancement: " + e.getMessage());
            }
        }
    }
    
    /**
     * 为终端输出区域启用指定的增强功能（带tabId）
     */
    public void enableEnhancementWithTabId(InlineCssTextArea outputArea, String tabId, 
                                          BiConsumer<InlineCssTextArea, String>... enhancers) {
        for (BiConsumer<InlineCssTextArea, String> enhancer : enhancers) {
            try {
                enhancer.accept(outputArea, tabId);
            } catch (Exception e) {
                System.err.println("Error applying enhancement with tabId: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取特定增强功能模块
     */
    public Object getEnhancementModule(String moduleName) {
        return enhancementModules.get(moduleName);
    }
    
    /**
     * 检查指定的增强功能模块是否存在
     */
    public boolean hasModule(String moduleName) {
        return enhancementModules.containsKey(moduleName);
    }
    
    /**
     * 动态注册新的增强模块
     */
    public void registerCustomModule(String name, Object module) {
        enhancementModules.put(name, module);
    }
    
    // 为向后兼容性，提供对ThemeEnhancer的静态访问
    public static class ThemeEnhancer extends com.runzhi.aishell.common.enhancement.ThemeEnhancer {
        // 这个嵌套类允许外部代码访问TerminalTheme枚举
    }
}