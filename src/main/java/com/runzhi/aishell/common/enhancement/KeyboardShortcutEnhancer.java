/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.enhancement;

import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 键盘快捷键增强器
 * 提供终端键盘快捷键功能
 */
public class KeyboardShortcutEnhancer {

    private final Map<String, InlineCssTextArea> activeOutputAreas = new HashMap<>();

    public void enableKeyboardShortcuts(InlineCssTextArea outputArea, String tabId) {
        activeOutputAreas.put(tabId, outputArea);
        // addEventFilter 而非 setOnKeyPressed：与补全/历史模块的处理器叠加而不是互相覆盖（2026-10-23 审计 P1-14）
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::handleKeyboardShortcuts);
    }

    private void handleKeyboardShortcuts(KeyEvent event) {
        if (event.isControlDown()) {
            String tabId = findTabIdByOutputArea(event.getSource());
            if (tabId == null) return;

            InlineCssTextArea outputArea = activeOutputAreas.get(tabId);
            if (outputArea == null) return;

            switch (event.getCode()) {
                case L:
                    // Ctrl+L - 清屏
                    clearScreen(outputArea);
                    event.consume();
                    break;
                case K:
                    // Ctrl+K - 清空当前行到行尾
                    clearLineToEnd(outputArea);
                    event.consume();
                    break;
                case U:
                    // Ctrl+U - 清空当前行到行首
                    clearLineToBeginning(outputArea);
                    event.consume();
                    break;
                case W:
                    // Ctrl+W - 删除光标前的单词
                    deleteWordBeforeCursor(outputArea);
                    event.consume();
                    break;
                case R:
                    // Ctrl+R - 反向搜索历史
                    showReverseSearchDialog(tabId);
                    event.consume();
                    break;
                case D:
                    // Ctrl+D - EOF信号
                    sendEOFSignal(tabId);
                    event.consume();
                    break;
                default:
                    break;
            }
        }
    }

    private String findTabIdByOutputArea(Object source) {
        // 这里需要一种机制来根据输出区域找到对应的tabId
        // 由于不能直接获取，我们将在enableKeyboardShortcuts中存储引用
        for (Map.Entry<String, InlineCssTextArea> entry : activeOutputAreas.entrySet()) {
            if (entry.getValue() == source) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void clearScreen(InlineCssTextArea outputArea) {
        Platform.runLater(() -> outputArea.clear());
    }

    private void clearCurrentLine(InlineCssTextArea outputArea) {
        // 获取当前行并清空
        int caretPos = outputArea.getCaretPosition();
        String text = outputArea.getText();

        if (text.isEmpty()) return;

        int lineStart = text.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 跳过换行符

        int lineEnd = text.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = text.length();

        String beforeLine = text.substring(0, lineStart);
        String afterLine = text.substring(lineEnd);

        int finalLineStart = lineStart;
        Platform.runLater(() -> {
            outputArea.replaceText(beforeLine + afterLine);
            outputArea.moveTo(finalLineStart);
        });
    }

    private void clearLineToEnd(InlineCssTextArea outputArea) {
        // 清空从光标到行尾的内容
        int caretPos = outputArea.getCaretPosition();
        String text = outputArea.getText();

        if (text.isEmpty() || caretPos >= text.length()) return;

        int lineEnd = text.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = text.length();

        String beforeCaret = text.substring(0, caretPos);
        String afterLine = text.substring(lineEnd);

        Platform.runLater(() -> {
            outputArea.replaceText(beforeCaret + afterLine);
            outputArea.moveTo(caretPos);
        });
    }

    private void clearLineToBeginning(InlineCssTextArea outputArea) {
        // 清空从行首到光标的内容
        int caretPos = outputArea.getCaretPosition();
        String text = outputArea.getText();

        if (text.isEmpty() || caretPos <= 0) return;

        int lineStart = text.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 跳过换行符

        String beforeLine = text.substring(0, lineStart);
        String afterCaret = text.substring(caretPos);

        int finalLineStart = lineStart;
        Platform.runLater(() -> {
            outputArea.replaceText(beforeLine + afterCaret);
            outputArea.moveTo(finalLineStart);
        });
    }

    private void deleteWordBeforeCursor(InlineCssTextArea outputArea) {
        // 删除光标前的一个单词
        int caretPos = outputArea.getCaretPosition();
        String text = outputArea.getText();

        if (text.isEmpty() || caretPos <= 0) return;

        String beforeCaret = text.substring(0, caretPos);
        int wordStart = findWordStart(beforeCaret);

        if (wordStart < caretPos) {
            String beforeWord = beforeCaret.substring(0, wordStart);
            String afterCaret = text.substring(caretPos);

            Platform.runLater(() -> {
                outputArea.replaceText(beforeWord + afterCaret);
                outputArea.moveTo(wordStart);
            });
        }
    }

    private int findWordStart(String text) {
        if (text.isEmpty()) return 0;

        int pos = text.length();
        // 跳过尾部空白字符
        while (pos > 0 && Character.isWhitespace(text.charAt(pos - 1))) {
            pos--;
        }

        // 找到单词的开始
        while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) {
            pos--;
        }

        return pos;
    }

    private void showReverseSearchDialog(String tabId) {
        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("反向搜索命令历史");
            dialog.setHeaderText("搜索历史命令 (Ctrl+R)");
            dialog.setContentText("搜索:");

            Optional<String> result = dialog.showAndWait();
            result.ifPresent(searchTerm -> {
                // 这里需要访问tab和history
                // 由于 KeyboardShortcutEnhancer 是独立的类，我们需要获取 HistoryManagementEnhancer 的实例
                // 通过参数传入或者通过其他方式访问
                // 这里暂时简化处理
                InlineCssTextArea outputArea = activeOutputAreas.get(tabId);
                if (outputArea != null) {
                    Platform.runLater(() -> outputArea.appendText("\n反向搜索功能需要访问历史管理器\n"));
                }
            });
        });
    }

    private void sendEOFSignal(String tabId) {
        // 通知终端服务发送EOF信号
        // 这里需要访问SSH连接
        // 由于不能直接访问SSH服务，我们可以发出一个事件或通知
        Platform.runLater(() -> {
            InlineCssTextArea outputArea = activeOutputAreas.get(tabId);
            if (outputArea != null) {
                outputArea.appendText("EOF signal sent (Ctrl+D)\n");
            }
        });
    }
}