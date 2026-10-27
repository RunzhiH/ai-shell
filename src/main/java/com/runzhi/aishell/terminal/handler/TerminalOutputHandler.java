/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.handler;

import com.runzhi.aishell.ssh.SSHConnectionManager;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;

/**
 * 终端输出处理器 - 专门处理终端输出的回调和显示
 */
@Slf4j
public class TerminalOutputHandler {
    
    /**
     * 创建输出回调函数，用于处理来自SSH连接的输出
     *
     * @param outputArea 终端输出区域
     * @return SSH连接的输出回调
     */
    public static SSHConnectionManager.ShellOutputCallback createOutputCallback(InlineCssTextArea outputArea) {
        return (text, color) -> {
            // 在UI线程中更新UI
            try {
                Platform.runLater(() -> {
                    int oldAnchor = outputArea.getAnchor();
                    int oldCaret = outputArea.getCaretPosition();
                    boolean followOutput = outputArea.getSelectedText().isEmpty()
                            && oldCaret >= outputArea.getLength();
                    // 使用ANSI颜色代码处理功能
                    AnsiColorProcessor.processAnsiTextFull(outputArea, text);

                    restoreCaretOrFollow(outputArea, oldAnchor, oldCaret, followOutput);

                    // 强制布局更新和视图刷新
                    outputArea.layout();
                    outputArea.applyCss();
                });
            } catch (Exception e) {
                log.error("Error updating UI: {}", e.getMessage(), e);
                // 如果Platform.runLater失败，尝试直接在当前线程中更新
                try {
                    outputArea.appendText("[UI Error: " + text + "]\n");
                } catch (Exception uiError) {
                    log.error("Failed to update UI even directly: {}", uiError.getMessage(), uiError);
                }
            }
        };
    }
    
    /**
     * 创建Shell风格的输出回调函数，会在命令执行后添加提示符
     *
     * @param outputArea 终端输出区域
     * @return SSH连接的输出回调
     */
    public static SSHConnectionManager.ShellOutputCallback createShellStyleOutputCallback(InlineCssTextArea outputArea) {
        return (text, color) -> {
            // 在UI线程中更新UI
            try {
                Platform.runLater(() -> {
                    int oldAnchor = outputArea.getAnchor();
                    int oldCaret = outputArea.getCaretPosition();
                    boolean followOutput = outputArea.getSelectedText().isEmpty()
                            && oldCaret >= outputArea.getLength();
                    // 使用ANSI颜色代码处理功能
                    AnsiColorProcessor.processAnsiTextFull(outputArea, text);

                    restoreCaretOrFollow(outputArea, oldAnchor, oldCaret, followOutput);

                    // 强制布局更新和视图刷新
                    outputArea.layout();
                    outputArea.applyCss();
                });
            } catch (Exception e) {
                log.error("Error updating UI: {}", e.getMessage(), e);
            }
        };
    }

    private static void restoreCaretOrFollow(InlineCssTextArea outputArea, int oldAnchor,
                                             int oldCaret, boolean followOutput) {
        if (followOutput) {
            outputArea.moveTo(outputArea.getLength());
            outputArea.requestFollowCaret();
        } else {
            int length = outputArea.getLength();
            outputArea.selectRange(Math.min(oldAnchor, length), Math.min(oldCaret, length));
        }
    }
    
    /**
     * 检查输出是否表示命令执行完成
     * 这是一个简单的启发式方法，可以根据实际情况进行改进
     */
    private static boolean isCommandExecutionComplete(String output) {
        // 检查是否包含换行符，这通常意味着命令执行完成
        // 或者包含某些特定的完成标记
        return output != null && (output.endsWith("\n") || output.endsWith("\r\n"));
    }
    
    /**
     * 处理ANSI颜色代码的内部工具类
     */
    public static class AnsiColorProcessor {
        /**
         * 完整处理ANSI颜色代码的方法
         *
         * @param textArea 文本区域
         * @param text     包含ANSI颜色代码的文本
         */
        public static void processAnsiTextFull(InlineCssTextArea textArea, String text) {
            // 首先清理控制序列，包括您提到的\u001B]0;...[u001B这类序列
            // cleanControlSequences 已不再剥 SGR(留给颜色处理器)，纯文本场景需显式去颜色码（2026-10-23 审计 P1-12）
            String cleanedText = com.runzhi.aishell.common.util.TerminalUtils.cleanControlSequences(text);
            cleanedText = com.runzhi.aishell.common.util.TerminalUtils.ANSI_COLOR_PATTERN.matcher(cleanedText).replaceAll("");

            // 处理回车符 \r - 将 \r\n 替换为 \n，单独的 \r 也替换为 \n
            cleanedText = cleanedText.replace("\r\n", "\n").replace("\r", "\n");
            
            // 同时匹配 \u001B (ESC) 和 \033 (八进制ESC) 格式
            cleanedText = cleanedText.replaceAll("\\033", "\\u001B");

            // ANSI转义序列的正则表达式，匹配 \u001B\[...m 格式
            java.util.regex.Pattern ansiPattern = java.util.regex.Pattern.compile("(\\u001B\\[([;\\d]*)m)");
            java.util.regex.Matcher matcher = ansiPattern.matcher(cleanedText);

            int lastMatchEnd = 0;
            String currentStyle = "-fx-fill: #ffffff;"; // 当前应用的样式，默认白色

            // 遍历所有ANSI转义序列
            while (matcher.find()) {
                // 添加转义序列之前的普通文本
                String precedingText = cleanedText.substring(lastMatchEnd, matcher.start());
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
            if (lastMatchEnd < cleanedText.length()) {
                String remainingText = cleanedText.substring(lastMatchEnd);
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

            // 确保应用CSS和布局更新。是否跟随末尾由外层根据用户当前选择状态决定。
            Platform.runLater(() -> {
                // 对于InlineCssTextArea，确保样式正确应用
                textArea.applyCss();
                textArea.layout();
            });
        }

        /**
         * 根据ANSI代码返回对应的CSS样式
         */
        public static String getStyleForAnsiCode(String ansiCode, String defaultStyle) {
            // ANSI颜色代码映射
            java.util.Map<String, String> ansiToCssMap = new java.util.HashMap<>();
            ansiToCssMap.put("0", ""); // 重置
            ansiToCssMap.put("1", "-fx-font-weight: bold;"); // 粗体
            ansiToCssMap.put("3", "-fx-font-style: italic;"); // 斜体
            ansiToCssMap.put("4", "-fx-underline: true;"); // 下划线
            ansiToCssMap.put("30", "-fx-fill: #000000;"); // 黑色
            ansiToCssMap.put("31", "-fx-fill: #ff0000;"); // 红色
            ansiToCssMap.put("32", "-fx-fill: #00ff00;"); // 绿色
            ansiToCssMap.put("33", "-fx-fill: #ffff00;"); // 黄色
            ansiToCssMap.put("34", "-fx-fill: #0000ff;"); // 蓝色
            ansiToCssMap.put("35", "-fx-fill: #ff00ff;"); // 紫色
            ansiToCssMap.put("36", "-fx-fill: #00ffff;"); // 青色
            ansiToCssMap.put("37", "-fx-fill: #ffffff;"); // 白色
            ansiToCssMap.put("90", "-fx-fill: #808080;"); // 亮黑色(灰色)
            ansiToCssMap.put("91", "-fx-fill: #ff8080;"); // 亮红色
            ansiToCssMap.put("92", "-fx-fill: #80ff80;"); // 亮绿色
            ansiToCssMap.put("93", "-fx-fill: #ffff80;"); // 亮黄色
            ansiToCssMap.put("94", "-fx-fill: #8080ff;"); // 亮蓝色
            ansiToCssMap.put("95", "-fx-fill: #ff80ff;"); // 亮紫色
            ansiToCssMap.put("96", "-fx-fill: #80ffff;"); // 亮青色
            ansiToCssMap.put("97", "-fx-fill: #ffffff;"); // 亮白色

            // 处理复合代码（如 "31;1" 表示红色粗体）
            String[] codes = ansiCode.split(";");
            StringBuilder styleBuilder = new StringBuilder();

            for (String code : codes) {
                String cssStyle = ansiToCssMap.get(code);
                if (cssStyle != null) {
                    styleBuilder.append(cssStyle).append(" ");
                }
            }

            if (styleBuilder.length() > 0) {
                return styleBuilder.toString().trim();
            } else {
                return defaultStyle;
            }
        }
    }
}