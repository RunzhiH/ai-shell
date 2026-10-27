/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.Collection;
import java.util.Collections;

/**
 * 语法高亮模块
 * 为终端输出提供语法高亮功能
 */
public class SyntaxHighlightingModule implements TerminalEnhancementModule {
    
    private final java.util.Set<String> enabledTabs = new java.util.HashSet<>();
    
    @Override
    public void initialize() {
        // 初始化语法高亮模块
    }

    @Override
    public void enable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.add(tabId);
        }
        
        // 应用基本的语法高亮样式
        applyBasicHighlighting(outputArea);
    }

    @Override
    public void disable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.remove(tabId);
        }
        
        // 移除语法高亮样式
        removeHighlighting(outputArea);
    }

    @Override
    public boolean isEnabled(String tabId) {
        return tabId != null && enabledTabs.contains(tabId);
    }

    @Override
    public String getModuleName() {
        return "syntaxHighlighting";
    }

    @Override
    public String getDescription() {
        return "为终端输出提供语法高亮功能";
    }
    
    /**
     * 应用基本的语法高亮
     */
    private void applyBasicHighlighting(InlineCssTextArea outputArea) {
        // 设置基本样式
        outputArea.setStyle(
            "-fx-font-family: 'Consolas', 'Monaco', 'Courier New', monospace; " +
            "-fx-font-size: 12px; " +
            "-fx-control-inner-background: #0d1117; " +  // GitHub Dark背景色
            "-fx-text-fill: #e6edf3; " +                 // 默认文字颜色
            "-fx-highlight-fill: #316dca; " +
            "-fx-highlight-text-fill: #ffffff; " +
            "-fx-prompt-text-fill: #888888; " +
            "-fx-background: #0d1117; " +
            "-fx-background-color: #0d1117; " +
            "-fx-border-color: #30363d; " +
            "-fx-border-width: 1px; " +
            "-fx-wrap-text: true;"
        );
    }
    
    /**
     * 移除语法高亮
     */
    private void removeHighlighting(InlineCssTextArea outputArea) {
        // 重置为默认样式
        outputArea.setStyle("");
    }
    
    /**
     * 针对CodeArea的高级语法高亮实现
     * 注意：由于模块系统限制，这里简化实现，不使用ReactFX的Subscription
     */
    public void enableAdvancedHighlighting(CodeArea codeArea, String language) {
        // 为CodeArea设置行号
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        
        // 这里原本会注册一个监听器来实时进行语法高亮
        // 但现在我们只做基本的设置，避免使用ReactFX的Subscription
        // 因为模块系统无法访问非模块化的ReactFX库
    }
    
    /**
     * 计算语法高亮样式
     * 注意：由于模块系统限制，这里返回空样式
     */
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeHighlighting(String text, String language) {
        // 根据语言类型计算不同的高亮样式
        // 但由于模块系统限制，这里简化实现
        return org.fxmisc.richtext.model.StyleSpans.singleton(Collections.emptyList(), text.length());
    }
    
    /**
     * 计算Shell脚本的语法高亮
     */
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeShellHighlighting(String text) {
        // 这里可以实现Shell语法的词法分析
        // 为简化，返回空样式
        return org.fxmisc.richtext.model.StyleSpans.singleton(Collections.emptyList(), text.length());
    }
    
    /**
     * 计算JSON的语法高亮
     */
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeJsonHighlighting(String text) {
        // 这里可以实现JSON语法的词法分析
        // 为简化，返回空样式
        return org.fxmisc.richtext.model.StyleSpans.singleton(Collections.emptyList(), text.length());
    }
    
    /**
     * 计算YAML的语法高亮
     */
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeYamlHighlighting(String text) {
        // 这里可以实现YAML语法的词法分析
        // 为简化，返回空样式
        return org.fxmisc.richtext.model.StyleSpans.singleton(Collections.emptyList(), text.length());
    }
    
    /**
     * 计算XML的语法高亮
     */
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeXmlHighlighting(String text) {
        // 这里可以实现XML语法的词法分析
        // 为简化，返回空样式
        return org.fxmisc.richtext.model.StyleSpans.singleton(Collections.emptyList(), text.length());
    }
}