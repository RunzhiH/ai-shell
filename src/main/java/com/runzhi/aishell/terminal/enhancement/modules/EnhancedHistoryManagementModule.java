/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.InlineCssTextArea;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 增强型历史管理模块
 * 提供更强大的命令历史管理功能
 */
public class EnhancedHistoryManagementModule implements TerminalEnhancementModule {
    
    private final Map<String, ObservableList<CommandEntry>> tabHistories = new ConcurrentHashMap<>();
    private final Map<String, Integer> tabCurrentHistoryIndexes = new ConcurrentHashMap<>();
    private final int maxHistorySize = 1000; // 增加历史记录的最大大小
    private final java.util.Map<InlineCssTextArea, javafx.event.EventHandler<javafx.scene.input.KeyEvent>> keyHandlers =
            new java.util.IdentityHashMap<>();

    public EnhancedHistoryManagementModule() {}

    @Override
    public void initialize() {
        // 初始化增强型历史管理模块
    }

    @Override
    public void enable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            // 初始化此标签页的历史记录
            if (!tabHistories.containsKey(tabId)) {
                tabHistories.put(tabId, FXCollections.observableArrayList());
            }
            
            // 初始化历史索引
            tabCurrentHistoryIndexes.put(tabId, -1);
            
            // 添加键盘事件监听器：必须用 addEventFilter，多个模块各自的 setOnKeyPressed 会互相覆盖，
            // 后注册的处理器会把历史导航/补全/快捷键全部吃掉（2026-10-23 审计 P1-14）
            javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler =
                    event -> handleHistoryNavigation(event, outputArea, tabId);
            keyHandlers.put(outputArea, handler);
            outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
        }
    }

    @Override
    public void disable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            tabHistories.remove(tabId);
            tabCurrentHistoryIndexes.remove(tabId);
        }
        // disable 必须真正摘除键盘过滤器，否则禁用后历史导航仍在工作（2026-10-23 审计终端-10）
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler = keyHandlers.remove(outputArea);
        if (handler != null) {
            outputArea.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
        }
    }

    @Override
    public boolean isEnabled(String tabId) {
        return tabId != null && tabHistories.containsKey(tabId);
    }

    @Override
    public String getModuleName() {
        return "enhanced-history-management";
    }

    @Override
    public String getDescription() {
        return "提供增强型命令历史管理功能";
    }
    
    /**
     * 处理历史记录导航
     */
    private void handleHistoryNavigation(KeyEvent event, InlineCssTextArea outputArea, String tabId) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        if (history == null || history.isEmpty()) {
            return;
        }
        
        Integer currentIndex = tabCurrentHistoryIndexes.get(tabId);
        
        if (event.getCode() == KeyCode.UP) {
            // 上箭头键 - 显示上一条命令
            if (currentIndex < history.size() - 1) {
                currentIndex = currentIndex + 1;
                tabCurrentHistoryIndexes.put(tabId, currentIndex);
                
                // 获取当前行的起始和结束位置
                String currentText = outputArea.getText();
                int caretPos = outputArea.getCaretPosition();
                
                int lineStart = findLineStart(currentText, caretPos);
                int lineEnd = findLineEnd(currentText, caretPos);
                
                // 替换当前行内容
                String beforeLine = currentText.substring(0, lineStart);
                String afterLine = currentText.substring(lineEnd);
                
                String command = history.get(history.size() - 1 - currentIndex).getCommand();
                
                outputArea.replaceText(beforeLine + command + afterLine);
                outputArea.moveTo(lineStart + command.length());
            }
            event.consume();
        } 
        else if (event.getCode() == KeyCode.DOWN) {
            // 下箭头键 - 显示下一条命令
            if (currentIndex > 0) {
                currentIndex = currentIndex - 1;
                tabCurrentHistoryIndexes.put(tabId, currentIndex);
                
                String currentText = outputArea.getText();
                int caretPos = outputArea.getCaretPosition();
                
                int lineStart = findLineStart(currentText, caretPos);
                int lineEnd = findLineEnd(currentText, caretPos);
                
                String beforeLine = currentText.substring(0, lineStart);
                String afterLine = currentText.substring(lineEnd);
                
                String command;
                if (currentIndex >= 0) {
                    command = history.get(history.size() - 1 - currentIndex).getCommand();
                } else {
                    // 如果到达历史记录底部，清空当前输入
                    command = "";
                }
                
                outputArea.replaceText(beforeLine + command + afterLine);
                outputArea.moveTo(lineStart + command.length());
            } else if (currentIndex == 0) {
                // 如果已经是最新命令，清空当前行
                String currentText = outputArea.getText();
                int caretPos = outputArea.getCaretPosition();
                
                int lineStart = findLineStart(currentText, caretPos);
                int lineEnd = findLineEnd(currentText, caretPos);
                
                String beforeLine = currentText.substring(0, lineStart);
                String afterLine = currentText.substring(lineEnd);
                
                outputArea.replaceText(beforeLine + afterLine);
                outputArea.moveTo(lineStart);
                
                tabCurrentHistoryIndexes.put(tabId, -1);
            }
            event.consume();
        }
        // Ctrl+R - 反向搜索历史命令
        else if (event.getCode() == KeyCode.R && event.isControlDown()) {
            performReverseSearch(outputArea, tabId);
            event.consume();
        }
        // Ctrl+S - 搜索历史命令
        else if (event.getCode() == KeyCode.S && event.isControlDown()) {
            performForwardSearch(outputArea, tabId);
            event.consume();
        }
    }
    
    /**
     * 执行反向搜索
     */
    private void performReverseSearch(InlineCssTextArea outputArea, String tabId) {
        // 这里可以实现更复杂的搜索功能
        // 由于不能直接显示对话框，我们可以将搜索结果显示在终端中
        outputArea.appendText("\n[历史搜索功能正在开发中]\n");
    }
    
    /**
     * 执行正向搜索
     */
    private void performForwardSearch(InlineCssTextArea outputArea, String tabId) {
        outputArea.appendText("\n[正向搜索功能正在开发中]\n");
    }
    
    /**
     * 添加命令到历史记录
     */
    public void addCommandToHistory(String tabId, String command) {
        if (tabId == null || command == null || command.trim().isEmpty()) {
            return;
        }
        
        ObservableList<CommandEntry> history = tabHistories.computeIfAbsent(tabId, k -> FXCollections.observableArrayList());
        
        // 避免重复命令连续出现
        if (history.isEmpty() || !history.get(history.size() - 1).getCommand().equals(command)) {
            history.add(new CommandEntry(command));
            
            // 限制历史记录大小
            if (history.size() > maxHistorySize) {
                history.remove(0);
            }
        }
        
        // 重置当前历史索引
        tabCurrentHistoryIndexes.put(tabId, -1);
    }
    
    /**
     * 获取特定标签页的历史记录
     */
    public List<String> getHistoryForTab(String tabId) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        if (history == null) {
            return new ArrayList<>();
        }
        
        return history.stream()
                     .map(CommandEntry::getCommand)
                     .collect(Collectors.toList());
    }
    
    /**
     * 清空特定标签页的历史记录
     */
    public void clearHistoryForTab(String tabId) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        if (history != null) {
            history.clear();
        }
        tabCurrentHistoryIndexes.put(tabId, -1);
    }
    
    /**
     * 获取最近的N条命令
     */
    public List<String> getRecentCommands(String tabId, int count) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        
        int startIdx = Math.max(0, history.size() - count);
        return history.subList(startIdx, history.size())
                     .stream()
                     .map(CommandEntry::getCommand)
                     .collect(Collectors.toList());
    }
    
    /**
     * 搜索历史记录中包含特定文本的命令
     */
    public List<String> searchHistory(String tabId, String searchText) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        if (history == null || searchText == null || searchText.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        return history.stream()
                     .filter(entry -> entry.getCommand().toLowerCase().contains(searchText.toLowerCase()))
                     .map(CommandEntry::getCommand)
                     .collect(Collectors.toList());
    }
    
    /**
     * 获取历史记录大小
     */
    public int getHistorySize(String tabId) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        return history != null ? history.size() : 0;
    }
    
    /**
     * 获取历史记录的统计信息
     */
    public String getHistoryStats(String tabId) {
        ObservableList<CommandEntry> history = tabHistories.get(tabId);
        if (history == null) {
            return "无历史记录";
        }
        
        if (history.isEmpty()) {
            return "历史记录为空";
        }
        
        // 计算最常用的命令
        Map<String, Long> commandFrequency = history.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                CommandEntry::getCommand,
                java.util.stream.Collectors.counting()
            ));
        
        String mostUsedCommand = commandFrequency.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("无");
        
        long totalCommands = history.size();
        long uniqueCommands = commandFrequency.size();
        
        return String.format(
            "总命令数: %d\n唯一命令数: %d\n最常用命令: %s\n历史记录大小: %d/%d",
            totalCommands, uniqueCommands, mostUsedCommand, totalCommands, maxHistorySize
        );
    }
    
    /**
     * 查找行的开始位置
     */
    private int findLineStart(String text, int caretPos) {
        if (text == null) return 0;
        int lineStart = text.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 如果没有找到换行符，从文本开头开始
        return lineStart;
    }

    /**
     * 查找行的结束位置
     */
    private int findLineEnd(String text, int caretPos) {
        if (text == null) return 0;
        int lineEnd = text.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = text.length(); // 如果没有找到换行符，到文本结尾结束
        return lineEnd;
    }
    
    /**
     * 命令条目内部类
     */
    public static class CommandEntry {
        private final String command;
        private final LocalDateTime timestamp;
        
        public CommandEntry(String command) {
            this.command = command;
            this.timestamp = LocalDateTime.now();
        }
        
        public String getCommand() {
            return command;
        }
        
        public LocalDateTime getTimestamp() {
            return timestamp;
        }
        
        public String getFormattedTimestamp() {
            return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        
        @Override
        public String toString() {
            return "[" + getFormattedTimestamp() + "] " + command;
        }
    }
}