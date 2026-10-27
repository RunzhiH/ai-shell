/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.enhancement;

import javafx.scene.input.KeyCode;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史管理增强器
 * 提供终端命令历史管理功能
 */
public class HistoryManagementEnhancer {
    private final Map<String, List<String>> tabHistories = new HashMap<>();
    private final Map<String, Integer> tabCurrentIndexes = new HashMap<>();
    /** 按上键导航前用户正在输入、尚未提交的内容；回到末尾时恢复（2026-10-23 审计 P1-15）。 */
    private final Map<String, String> tabPendingInputs = new HashMap<>();

    public void enableHistoryManagement(InlineCssTextArea outputArea, String tabId) {
        // 初始化该标签页的历史记录
        if (!tabHistories.containsKey(tabId)) {
            tabHistories.put(tabId, new ArrayList<>());
            tabCurrentIndexes.put(tabId, -1);
        }

        // 监听键盘事件以处理上下箭头键：addEventFilter 避免被后注册的模块覆盖（2026-10-23 审计 P1-14）
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.UP) {
                navigateHistoryUp(outputArea, tabId);
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                navigateHistoryDown(outputArea, tabId);
                event.consume();
            }
        });
    }

    private void navigateHistoryUp(InlineCssTextArea outputArea, String tabId) {
        List<String> history = tabHistories.get(tabId);
        if (history == null || history.isEmpty()) return;

        int currentIndex = tabCurrentIndexes.get(tabId);
        if (currentIndex < 0) {
            // 第一次向上导航：暂存当前未提交输入，并把索引同步到末尾位置；
            // 只 put 不赋给 currentIndex 会让它保持 -1，首次按上键毫无反应（2026-10-23 审计 P1-15）
            String currentInput = getCurrentInputText(outputArea);
            tabPendingInputs.put(tabId, currentInput == null ? "" : currentInput.trim());
            currentIndex = history.size();
        }

        if (currentIndex > 0) {
            currentIndex--;
            String command = history.get(currentIndex);

            // 替换当前行内容
            replaceCurrentLine(outputArea, command);

            tabCurrentIndexes.put(tabId, currentIndex);
        }
    }

    private void navigateHistoryDown(InlineCssTextArea outputArea, String tabId) {
        List<String> history = tabHistories.get(tabId);
        if (history == null) return;

        int currentIndex = tabCurrentIndexes.get(tabId);
        if (currentIndex < history.size() - 1) {
            currentIndex++;
            String command = history.get(currentIndex);

            // 替换当前行内容
            replaceCurrentLine(outputArea, command);

            tabCurrentIndexes.put(tabId, currentIndex);
        } else if (currentIndex == history.size() - 1) {
            // 已经是最后一条历史命令：恢复用户按上键之前正在输入的内容，而不是直接清空（2026-10-23 审计终端-19）
            String pending = tabPendingInputs.get(tabId);
            replaceCurrentLine(outputArea, pending == null ? "" : pending);
            tabCurrentIndexes.put(tabId, history.size());
        }
        // 已在末尾之后再按 DOWN：无操作（标准 shell 行为）
    }

    public void addToHistory(String tabId, String command) {
        if (tabId == null || command == null || command.trim().isEmpty()) return;

        List<String> history = tabHistories.computeIfAbsent(tabId, k -> new ArrayList<>());

        // 避免重复命令
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
        }

        // 重置当前索引
        tabCurrentIndexes.put(tabId, history.size());
    }

    public List<String> getHistoryForTab(String tabId) {
        return tabHistories.getOrDefault(tabId, new ArrayList<>());
    }

    /**
     * 清空指定标签页的命令历史
     */
    public void clearHistoryForTab(String tabId) {
        if (tabHistories.containsKey(tabId)) {
            tabHistories.get(tabId).clear();
            tabCurrentIndexes.put(tabId, -1);
        }
    }

    /**
     * 获取历史记录大小
     */
    public int getHistorySize(String tabId) {
        return tabHistories.getOrDefault(tabId, new ArrayList<>()).size();
    }

    /**
     * 搜索历史命令
     */
    public List<String> searchHistory(String tabId, String searchTerm) {
        List<String> history = getHistoryForTab(tabId);
        List<String> results = new ArrayList<>();

        if (searchTerm == null || searchTerm.isEmpty()) {
            return results;
        }

        for (String command : history) {
            if (command != null && command.toLowerCase().contains(searchTerm.toLowerCase())) {
                results.add(command);
            }
        }

        return results;
    }

    /**
     * 获取最近的N条命令
     */
    public List<String> getRecentCommands(String tabId, int count) {
        List<String> history = getHistoryForTab(tabId);
        int size = history.size();

        if (count >= size) {
            return new ArrayList<>(history);
        }

        int startIndex = size - count;
        return new ArrayList<>(history.subList(startIndex, size));
    }

    private String getCurrentInputText(InlineCssTextArea textArea) {
        if (textArea == null) return null;
        int caretPos = textArea.getCaretPosition();
        String fullText = textArea.getText();

        if (fullText == null || fullText.isEmpty() || caretPos < 0) {
            return "";
        }

        // 从光标位置截取文本
        String textFromCaret = fullText.substring(caretPos);

        // 返回第一个换行符之前的内容（当前输入行的剩余部分）
        int firstNewlineIndex = textFromCaret.indexOf('\n');
        if (firstNewlineIndex != -1) {
            return textFromCaret.substring(0, firstNewlineIndex);
        } else {
            return textFromCaret;
        }
    }

    private void replaceCurrentLine(InlineCssTextArea outputArea, String newText) {
        String fullText = outputArea.getText();
        if (fullText == null) return;

        int caretPos = outputArea.getCaretPosition();

        // 查找当前行的开始和结束位置
        int lineStart = fullText.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 如果没有找到换行符，从文本开头开始

        int lineEnd = fullText.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = fullText.length(); // 如果没有找到换行符，到文本结尾结束

        String beforeLine = fullText.substring(0, lineStart);
        String afterLine = fullText.substring(lineEnd);

        outputArea.replaceText(beforeLine + newText + afterLine);
        outputArea.moveTo(lineStart + newText.length());
    }
}