/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANSI颜色处理器，专门处理ANSI颜色代码的解析和应用
 */
@Slf4j
public class AnsiColorProcessor {

    // ANSI转义序列的正则表达式，匹配 \u001B\[...m 格式
    private static final Pattern ANSI_PATTERN = Pattern.compile("(\\u001B\\[([;\\d]*)m)");
    
    // 预定义ANSI颜色代码映射，避免每次都创建新的HashMap
    private static final Map<String, String> ANSI_TO_CSS_MAP = new HashMap<>();
    static {
        // 基本颜色代码
        ANSI_TO_CSS_MAP.put("30", "-fx-fill: #000000;"); // 黑色
        ANSI_TO_CSS_MAP.put("31", "-fx-fill: #ff0000;"); // 红色
        ANSI_TO_CSS_MAP.put("32", "-fx-fill: #00ff00;"); // 绿色
        ANSI_TO_CSS_MAP.put("33", "-fx-fill: #ffff00;"); // 黄色
        ANSI_TO_CSS_MAP.put("34", "-fx-fill: #0000ff;"); // 蓝色
        ANSI_TO_CSS_MAP.put("35", "-fx-fill: #ff00ff;"); // 紫色
        ANSI_TO_CSS_MAP.put("36", "-fx-fill: #00ffff;"); // 青色
        ANSI_TO_CSS_MAP.put("37", "-fx-fill: #ffffff;"); // 白色
        ANSI_TO_CSS_MAP.put("39", "-fx-fill: #d4d4d4;"); // 默认前景色

        // Bright颜色代码
        ANSI_TO_CSS_MAP.put("90", "-fx-fill: #808080;"); // 亮黑色(灰色)
        ANSI_TO_CSS_MAP.put("91", "-fx-fill: #ff6b6b;"); // 亮红色
        ANSI_TO_CSS_MAP.put("92", "-fx-fill: #51cf66;"); // 亮绿色
        ANSI_TO_CSS_MAP.put("93", "-fx-fill: #fcc419;"); // 亮黄色
        ANSI_TO_CSS_MAP.put("94", "-fx-fill: #339af0;"); // 亮蓝色
        ANSI_TO_CSS_MAP.put("95", "-fx-fill: #be4bdb;"); // 亮紫色
        ANSI_TO_CSS_MAP.put("96", "-fx-fill: #20c997;"); // 亮青色
        ANSI_TO_CSS_MAP.put("97", "-fx-fill: #f8f9fa;"); // 亮白色

        // 背景颜色代码
        ANSI_TO_CSS_MAP.put("40", "-fx-background-color: #000000;"); // 黑色背景
        ANSI_TO_CSS_MAP.put("41", "-fx-background-color: #ff0000;"); // 红色背景
        ANSI_TO_CSS_MAP.put("42", "-fx-background-color: #00ff00;"); // 绿色背景
        ANSI_TO_CSS_MAP.put("43", "-fx-background-color: #ffff00;"); // 黄色背景
        ANSI_TO_CSS_MAP.put("44", "-fx-background-color: #0000ff;"); // 蓝色背景
        ANSI_TO_CSS_MAP.put("45", "-fx-background-color: #ff00ff;"); // 紫色背景
        ANSI_TO_CSS_MAP.put("46", "-fx-background-color: #00ffff;"); // 青色背景
        ANSI_TO_CSS_MAP.put("47", "-fx-background-color: #ffffff;"); // 白色背景
        ANSI_TO_CSS_MAP.put("49", "-fx-background-color: #000000;"); // 默认背景色

        // Bright背景颜色代码
        ANSI_TO_CSS_MAP.put("100", "-fx-background-color: #808080;"); // 亮黑背景(灰)
        ANSI_TO_CSS_MAP.put("101", "-fx-background-color: #ff6b6b;"); // 亮红背景
        ANSI_TO_CSS_MAP.put("102", "-fx-background-color: #51cf66;"); // 亮绿背景
        ANSI_TO_CSS_MAP.put("103", "-fx-background-color: #fcc419;"); // 亮黄背景
        ANSI_TO_CSS_MAP.put("104", "-fx-background-color: #339af0;"); // 亮蓝背景
        ANSI_TO_CSS_MAP.put("105", "-fx-background-color: #be4bdb;"); // 亮紫背景
        ANSI_TO_CSS_MAP.put("106", "-fx-background-color: #20c997;"); // 亮青背景
        ANSI_TO_CSS_MAP.put("107", "-fx-background-color: #f8f9fa;"); // 亮白背景

        // 格式代码
        ANSI_TO_CSS_MAP.put("1", "-fx-font-weight: bold;"); // 加粗
        ANSI_TO_CSS_MAP.put("2", "-fx-opacity: 0.6;"); // 暗淡/半透明
        ANSI_TO_CSS_MAP.put("3", "-fx-font-style: italic;"); // 斜体
        ANSI_TO_CSS_MAP.put("4", "-fx-underline: true;"); // 下划线
        ANSI_TO_CSS_MAP.put("5", "-fx-effect: innershadow(gaussian, #ffffff, 5, 0, 0, 0);"); // 闪烁
        ANSI_TO_CSS_MAP.put("6", "-fx-effect: innershadow(gaussian, #ffffff, 5, 0, 0, 0);"); // 快速闪烁
        ANSI_TO_CSS_MAP.put("7", "-fx-background-color: #ffffff; -fx-fill: #000000;"); // 反转颜色
        ANSI_TO_CSS_MAP.put("8", "-fx-opacity: 0.0;"); // 隐藏
        ANSI_TO_CSS_MAP.put("9", "-fx-strikethrough: true;"); // 删除线
        ANSI_TO_CSS_MAP.put("21", "-fx-underline: true; -fx-underline-width: 2;"); // 双下划线
        ANSI_TO_CSS_MAP.put("22", "-fx-font-weight: normal; -fx-opacity: 1.0;"); // 正常粗细和不透明
        ANSI_TO_CSS_MAP.put("23", "-fx-font-style: normal;"); // 正常字体
        ANSI_TO_CSS_MAP.put("24", "-fx-underline: false;"); // 无下划线
        ANSI_TO_CSS_MAP.put("25", "-fx-effect: null;"); // 无闪烁
        ANSI_TO_CSS_MAP.put("27", "-fx-background-color: #000000; -fx-fill: #000000;"); // 反转颜色
        ANSI_TO_CSS_MAP.put("28", "-fx-opacity: 1.0;"); // 不隐藏
        ANSI_TO_CSS_MAP.put("29", "-fx-strikethrough: false;"); // 无删除线
    }

    /**
     * 完整处理ANSI颜色代码的方法
     *
     * @param textArea 文本区域
     * @param text     包含ANSI颜色代码的文本
     */
    public static void processAnsiTextFull(InlineCssTextArea textArea, String text) {
        if (textArea == null || text == null) return;
        
        // 同时匹配 \u001B (ESC) 和 \033 (八进制ESC) 格式
        String processedText = text.replaceAll("\\033", "\\u001B");

        // 清理控制序列
        processedText = TerminalUtils.cleanControlSequences(processedText);

        Matcher matcher = ANSI_PATTERN.matcher(processedText);

        int lastMatchEnd = 0;
        String currentStyle = "-fx-fill: #ffffff;"; // 当前应用的样式，默认白色

        // 遍历所有ANSI转义序列
        while (matcher.find()) {
            // 添加转义序列之前的普通文本
            String precedingText = processedText.substring(lastMatchEnd, matcher.start());
            if (!precedingText.isEmpty()) {
                int startPos = textArea.getLength();
                textArea.appendText(precedingText);
                int endPos = textArea.getLength();

                // 应用当前样式
                if (currentStyle != null && !currentStyle.trim().isEmpty()) {
                    textArea.setStyle(startPos, endPos, currentStyle);
                }
            }

            // 处理ANSI代码
            String ansiCodeStr = matcher.group(2); // 获取ANSI代码部分（不含转义字符和m）
            if (ansiCodeStr != null && !ansiCodeStr.isEmpty() && !"0".equals(ansiCodeStr)) {
                // 如果不是重置代码，更新当前样式
                currentStyle = getStyleForAnsiCode(ansiCodeStr, "-fx-fill: #ffffff;");
            } else if ("0".equals(ansiCodeStr)) {
                // 重置代码，恢复默认样式
                currentStyle = "-fx-fill: #ffffff;";
            }
            // 如果ansiCodeStr为空（即"\u001B[m"），保持当前样式不变

            lastMatchEnd = matcher.end();
        }

        // 添加最后一段文本（如果有的话）
        if (lastMatchEnd < processedText.length()) {
            String remainingText = processedText.substring(lastMatchEnd);
            if (!remainingText.isEmpty()) {
                int startPos = textArea.getLength();
                textArea.appendText(remainingText);
                int endPos = textArea.getLength();

                // 应用当前样式
                if (currentStyle != null && !currentStyle.trim().isEmpty()) {
                    textArea.setStyle(startPos, endPos, currentStyle);
                }
            }
        }

        // 确保应用CSS和布局更新
        Platform.runLater(() -> {
            // 对于InlineCssTextArea，确保样式正确应用
            textArea.applyCss();
            textArea.layout();
            // 请求跟随光标，确保最新内容可见
            textArea.requestFollowCaret();
        });
    }

    /**
     * 根据ANSI代码返回对应的CSS样式
     */
    public static String getStyleForAnsiCode(String ansiCode, String defaultStyle) {
        if (ansiCode == null) return defaultStyle;
        
        String[] codes = ansiCode.split(";");
        StringBuilder style = new StringBuilder();
        for (String code : codes) {
            if (code != null) {
                String trimmedCode = code.trim();
                String cssStyle = ANSI_TO_CSS_MAP.get(trimmedCode);
                if (cssStyle != null) {
                    style.append(cssStyle).append(" ");
                }
            }
        }

        return style.length() > 0 ? style.toString().trim() : defaultStyle;
    }

    /**
     * 测试ANSI颜色代码
     */
    public static void testAnsiColors(InlineCssTextArea textArea) {
        if (textArea == null) return;
        Platform.runLater(() -> {
            textArea.appendText("测试ANSI颜色代码:\n");
            textArea.appendText("\u001B[31m红色文字\u001B[0m\n");
            textArea.appendText("\u001B[32m绿色文字\u001B[0m\n");
            textArea.appendText("\u001B[33m黄色文字\u001B[0m\n");
            textArea.appendText("\u001B[34m蓝色文字\u001B[0m\n");
            textArea.appendText("\u001B[35m紫色文字\u001B[0m\n");
            textArea.appendText("\u001B[36m青色文字\u001B[0m\n");
            textArea.appendText("\u001B[1m粗体文字\u001B[0m\n");
            textArea.appendText("\u001B[4m下划线文字\u001B[0m\n");
        });
    }
}