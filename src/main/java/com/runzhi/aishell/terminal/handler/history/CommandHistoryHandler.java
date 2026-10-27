/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.handler.history;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令历史处理器 - 专门处理命令历史相关的逻辑
 */
@Slf4j
public class CommandHistoryHandler {
    
    // 为每个Tab维护命令历史
    private final Map<javafx.scene.control.Tab, List<String>> tabCommandHistory = new HashMap<>();
    private final Map<javafx.scene.control.Tab, Integer> tabCurrentHistoryIndex = new HashMap<>();
    
    /**
     * 初始化标签页的命令历史
     */
    public void initializeTabCommandHistory(javafx.scene.control.Tab tab) {
        if (!tabCommandHistory.containsKey(tab)) {
            tabCommandHistory.put(tab, new ArrayList<>());
            tabCurrentHistoryIndex.put(tab, -1);
        }
    }
    
    /**
     * 添加命令到历史记录
     */
    public void addCommandToHistory(javafx.scene.control.Tab tab, String command) {
        if (tab == null || command == null || command.trim().isEmpty()) {
            return;
        }
        
        initializeTabCommandHistory(tab);
        
        List<String> history = tabCommandHistory.get(tab);
        // 避免重复命令
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
        }
        
        // 更新当前索引到最新位置
        // 指向历史末尾之后，第一次按 Up 应显示最新一条命令。
        tabCurrentHistoryIndex.put(tab, history.size());
    }
    
    /**
     * 设置命令历史导航
     */
    public void setupCommandHistoryNavigation(TextField commandInput, javafx.scene.control.Tab tab) {
        if (commandInput == null || tab == null) {
            return;
        }
        
        initializeTabCommandHistory(tab);
        
        commandInput.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            List<String> history = tabCommandHistory.get(tab);
            if (history == null || history.isEmpty()) {
                return;
            }
            
            Integer currentIndex = tabCurrentHistoryIndex.get(tab);
            if (currentIndex == null) {
                currentIndex = -1;
            }
            
            if (event.getCode() == KeyCode.UP) {
                // 上方向键：获取上一条命令
                if (!history.isEmpty() && currentIndex > 0) {
                    currentIndex = Math.min(currentIndex, history.size()) - 1;
                    commandInput.setText(history.get(currentIndex));
                    commandInput.positionCaret(commandInput.getText().length());
                    tabCurrentHistoryIndex.put(tab, currentIndex);
                }
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                // 下方向键：获取下一条命令
                if (!history.isEmpty() && currentIndex < history.size() - 1) {
                    currentIndex++;
                    if (currentIndex < history.size()) {
                        commandInput.setText(history.get(currentIndex));
                        commandInput.positionCaret(commandInput.getText().length()); // 将光标移到末尾
                    } else {
                        commandInput.clear(); // 如果到达最后，清空输入框
                    }
                    tabCurrentHistoryIndex.put(tab, currentIndex);
                } else if (currentIndex >= history.size() - 1) {
                    commandInput.clear(); // 如果已经在最后，清空输入框
                    tabCurrentHistoryIndex.put(tab, history.size());
                }
                event.consume();
            }
        });
    }
    
    /**
     * 获取指定标签页的命令历史
     */
    public List<String> getHistoryForTab(javafx.scene.control.Tab tab) {
        if (tab == null) {
            return new ArrayList<>();
        }
        
        initializeTabCommandHistory(tab);
        return new ArrayList<>(tabCommandHistory.get(tab));
    }
    
    /**
     * 获取标签页的当前历史索引
     */
    public Integer getCurrentHistoryIndex(javafx.scene.control.Tab tab) {
        if (tab == null) {
            return -1;
        }
        
        initializeTabCommandHistory(tab);
        return tabCurrentHistoryIndex.get(tab);
    }
    
    /**
     * 清空指定标签页的命令历史
     */
    public void clearHistoryForTab(javafx.scene.control.Tab tab) {
        if (tab == null) {
            return;
        }
        
        tabCommandHistory.put(tab, new ArrayList<>());
        tabCurrentHistoryIndex.put(tab, -1);
    }
    
    /**
     * 获取所有标签页的命令历史
     */
    public Map<javafx.scene.control.Tab, List<String>> getAllTabCommandHistories() {
        Map<javafx.scene.control.Tab, List<String>> copy = new HashMap<>();
        for (Map.Entry<javafx.scene.control.Tab, List<String>> entry : tabCommandHistory.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }
}
