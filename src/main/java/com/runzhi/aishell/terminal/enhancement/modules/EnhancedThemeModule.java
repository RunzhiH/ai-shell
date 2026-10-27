/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import org.fxmisc.richtext.InlineCssTextArea;

import java.util.HashMap;
import java.util.Map;

/**
 * 增强型主题模块
 * 提供更多样化的终端主题和高级主题管理功能
 */
public class EnhancedThemeModule implements TerminalEnhancementModule {
    
    private final Map<String, Boolean> enabledTabs = new HashMap<>();
    private final Map<String, TerminalTheme> tabThemes = new HashMap<>();
    private TerminalTheme defaultTheme = TerminalTheme.DARK_TERMINAL;
    
    public EnhancedThemeModule() {}

    @Override
    public void initialize() {
        // 初始化增强型主题模块
    }

    @Override
    public void enable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.put(tabId, true);
            // 为新标签页设置默认主题
            if (!tabThemes.containsKey(tabId)) {
                tabThemes.put(tabId, defaultTheme);
            }
            applyTheme(outputArea, tabThemes.get(tabId));
        }
    }

    @Override
    public void disable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.remove(tabId);
            tabThemes.remove(tabId);
        }
    }

    @Override
    public boolean isEnabled(String tabId) {
        return tabId != null && enabledTabs.containsKey(tabId);
    }

    @Override
    public String getModuleName() {
        return "enhanced-theme";
    }

    @Override
    public String getDescription() {
        return "提供增强型终端主题功能";
    }
    
    /**
     * 应用指定主题到输出区域
     */
    public void applyTheme(InlineCssTextArea outputArea, TerminalTheme theme) {
        if (outputArea == null || theme == null) return;
        
        String cssStyle = buildThemeStyle(theme);
        outputArea.setStyle(cssStyle);
        
        // 确保样式生效
        outputArea.applyCss();
        outputArea.layout();
    }
    
    /**
     * 应用指定主题到输出区域（通过主题名称）
     */
    public void applyTheme(InlineCssTextArea outputArea, String themeName) {
        TerminalTheme theme = TerminalTheme.fromString(themeName);
        if (theme != null) {
            applyTheme(outputArea, theme);
        }
    }
    
    /**
     * 为标签页设置主题
     */
    public void setTabTheme(String tabId, TerminalTheme theme) {
        if (tabId != null && theme != null) {
            tabThemes.put(tabId, theme);
        }
    }
    
    /**
     * 获取标签页的当前主题
     */
    public TerminalTheme getTabTheme(String tabId) {
        if (tabId != null) {
            return tabThemes.getOrDefault(tabId, defaultTheme);
        }
        return defaultTheme;
    }
    
    /**
     * 设置默认主题
     */
    public void setDefaultTheme(TerminalTheme theme) {
        if (theme != null) {
            this.defaultTheme = theme;
        }
    }
    
    /**
     * 获取默认主题
     */
    public TerminalTheme getDefaultTheme() {
        return defaultTheme;
    }
    
    /**
     * 构建主题样式
     */
    private String buildThemeStyle(TerminalTheme theme) {
        StringBuilder style = new StringBuilder();
        
        switch (theme) {
            case DARK_TERMINAL:
                style.append(buildDarkTerminalStyle());
                break;
            case LIGHT_TERMINAL:
                style.append(buildLightTerminalStyle());
                break;
            case MONOKAI:
                style.append(buildMonokaiStyle());
                break;
            case SOLARIZED_DARK:
                style.append(buildSolarizedDarkStyle());
                break;
            case SOLARIZED_LIGHT:
                style.append(buildSolarizedLightStyle());
                break;
            case GRUVBOX:
                style.append(buildGruvboxStyle());
                break;
            case DRACULA:
                style.append(buildDraculaStyle());
                break;
            case ONE_DARK:
                style.append(buildOneDarkStyle());
                break;
            case MATERIAL:
                style.append(buildMaterialStyle());
                break;
            case CUSTOM:
                style.append(buildCustomStyle());
                break;
            default:
                style.append(buildDarkTerminalStyle());
                break;
        }
        
        return style.toString();
    }
    
    /**
     * 构建暗色终端样式
     */
    private String buildDarkTerminalStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #0d1117; " +  // GitHub Dark背景色
            "-fx-text-fill: #e6edf3; " +                 // GitHub Dark文字色
            "-fx-highlight-fill: #316dca; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #888888; " +
            "-fx-background: #0d1117; " +
            "-fx-background-color: #0d1117; " +
            "-fx-border-color: #30363d; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建亮色终端样式
     */
    private String buildLightTerminalStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #ffffff; " +
            "-fx-text-fill: #000000; " +
            "-fx-highlight-fill: #316dca; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #888888; " +
            "-fx-background: #ffffff; " +
            "-fx-background-color: #ffffff; " +
            "-fx-border-color: #cccccc; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建Monokai主题样式
     */
    private String buildMonokaiStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #272822; " +  // Monokai背景
            "-fx-text-fill: #f8f8f2; " +                 // Monokai文字
            "-fx-highlight-fill: #4a90d9; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #888888; " +
            "-fx-background: #272822; " +
            "-fx-background-color: #272822; " +
            "-fx-border-color: #75715e; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建Solarized Dark主题样式
     */
    private String buildSolarizedDarkStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #002b36; " +  // Solarized Dark背景
            "-fx-text-fill: #839496; " +                 // Solarized base0
            "-fx-highlight-fill: #268bd2; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #586e75; " +
            "-fx-background: #002b36; " +
            "-fx-background-color: #002b36; " +
            "-fx-border-color: #073642; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建Solarized Light主题样式
     */
    private String buildSolarizedLightStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #fdf6e3; " +  // Solarized Light背景
            "-fx-text-fill: #657b83; " +                 // Solarized base00
            "-fx-highlight-fill: #268bd2; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #93a1a1; " +
            "-fx-background: #fdf6e3; " +
            "-fx-background-color: #fdf6e3; " +
            "-fx-border-color: #eee8d5; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建Gruvbox主题样式
     */
    private String buildGruvboxStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #282828; " +  // Gruvbox背景
            "-fx-text-fill: #ebdbb2; " +                 // Gruvbox前景
            "-fx-highlight-fill: #458588; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #928374; " +
            "-fx-background: #282828; " +
            "-fx-background-color: #282828; " +
            "-fx-border-color: #3c3836; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建Dracula主题样式
     */
    private String buildDraculaStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #282a36; " +  // Dracula背景
            "-fx-text-fill: #f8f8f2; " +                 // Dracula前景
            "-fx-highlight-fill: #bd93f9; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #6272a4; " +
            "-fx-background: #282a36; " +
            "-fx-background-color: #282a36; " +
            "-fx-border-color: #44475a; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建One Dark主题样式
     */
    private String buildOneDarkStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #282c34; " +  // One Dark背景
            "-fx-text-fill: #abb2bf; " +                 // One Dark前景
            "-fx-highlight-fill: #61afef; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #545862; " +
            "-fx-background: #282c34; " +
            "-fx-background-color: #282c34; " +
            "-fx-border-color: #3e4452; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建Material主题样式
     */
    private String buildMaterialStyle() {
        return 
            "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #263238; " +  // Material背景
            "-fx-text-fill: #eeffff; " +                 // Material前景
            "-fx-highlight-fill: #84ffff; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #546e7a; " +
            "-fx-background: #263238; " +
            "-fx-background-color: #263238; " +
            "-fx-border-color: #37474f; " +
            "-fx-border-width: 1px; " +
            "-fx-opacity: 1.0; " +
            "-fx-visible: true; " +
            "-fx-background-radius: 6px; " +
            "-fx-border-radius: 6px; " +
            "-fx-padding: 10px; " +
            "-fx-cursor: text;";
    }
    
    /**
     * 构建自定义样式
     */
    private String buildCustomStyle() {
        return buildDarkTerminalStyle(); // 默认使用暗色主题
    }
    
    /**
     * 终端主题枚举
     */
    public enum TerminalTheme {
        DARK_TERMINAL("Dark Terminal"),
        LIGHT_TERMINAL("Light Terminal"),
        MONOKAI("Monokai"),
        SOLARIZED_DARK("Solarized Dark"),
        SOLARIZED_LIGHT("Solarized Light"),
        GRUVBOX("Gruvbox"),
        DRACULA("Dracula"),
        ONE_DARK("One Dark"),
        MATERIAL("Material"),
        CUSTOM("Custom");
        
        private final String displayName;
        
        TerminalTheme(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public static TerminalTheme fromString(String themeName) {
            for (TerminalTheme theme : TerminalTheme.values()) {
                if (theme.name().equalsIgnoreCase(themeName) || 
                    theme.displayName.equalsIgnoreCase(themeName)) {
                    return theme;
                }
            }
            return DARK_TERMINAL; // 默认返回暗色主题
        }
    }
}