/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.history;

import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 命令历史管理器，负责管理每个标签页的命令历史
 */
@Slf4j
public class CommandHistoryManager {

    // 为每个Tab维护命令历史
    private final Map<Tab, List<String>> tabCommandHistory = new HashMap<>();
    private final Map<Tab, Integer> tabCurrentHistoryIndex = new HashMap<>();

    /**
     * 初始化标签页的命令历史
     */
    public void initializeTabCommandHistory(Tab tab) {
        if (tab == null) {
            log.warn("标签页为空，无法初始化命令历史");
            return;
        }

        if (!tabCommandHistory.containsKey(tab)) {
            tabCommandHistory.put(tab, new ArrayList<>());
            tabCurrentHistoryIndex.put(tab, -1);
        }
    }

    /**
     * 为标签页添加命令到历史记录
     */
    public void addCommandToHistory(Tab tab, String command) {
        if (tab == null || command == null || command.trim().isEmpty()) {
            return;
        }

        initializeTabCommandHistory(tab);

        List<String> history = tabCommandHistory.get(tab);
        if (history == null) {
            history = new ArrayList<>();
            tabCommandHistory.put(tab, history);
        }

        // 避免重复命令连续出现
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
            log.debug("添加命令到历史: {}", command);
        }

        // 重置历史索引
        tabCurrentHistoryIndex.put(tab, history.size());
    }

    /**
     * 设置命令历史导航功能
     */
    public void setupCommandHistoryNavigation(TextField commandInput, Tab tab) {
        if (commandInput == null || tab == null) {
            return;
        }

        initializeTabCommandHistory(tab);

        commandInput.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.UP) {
                // 上方向键：获取上一条命令
                String prevCommand = getPreviousCommand(tab);
                if (prevCommand != null && !prevCommand.isEmpty()) {
                    commandInput.setText(prevCommand);
                    commandInput.positionCaret(prevCommand.length());
                }
                event.consume();
            } else if (event.getCode() == javafx.scene.input.KeyCode.DOWN) {
                // 下方向键：获取下一条命令
                String nextCommand = getNextCommand(tab);
                if (nextCommand != null && !nextCommand.isEmpty()) {
                    commandInput.setText(nextCommand);
                    commandInput.positionCaret(nextCommand.length());
                } else {
                    // 如果是最后一条命令，清空输入框
                    commandInput.clear();
                }
                event.consume();
            }
        });
    }

    /**
     * 获取上一条命令
     */
    public String getPreviousCommand(Tab tab) {
        if (tab == null) {
            return null;
        }

        List<String> history = tabCommandHistory.get(tab);
        Integer currentIndex = tabCurrentHistoryIndex.get(tab);

        if (history == null || history.isEmpty()) {
            return null;
        }

        if (currentIndex == null) {
            currentIndex = history.size();
            tabCurrentHistoryIndex.put(tab, currentIndex);
        }

        if (currentIndex > 0) {
            currentIndex--;
            tabCurrentHistoryIndex.put(tab, currentIndex);
            return history.get(currentIndex);
        }

        return null;
    }

    /**
     * 获取下一条命令
     */
    public String getNextCommand(Tab tab) {
        if (tab == null) {
            return null;
        }

        List<String> history = tabCommandHistory.get(tab);
        Integer currentIndex = tabCurrentHistoryIndex.get(tab);

        if (history == null || history.isEmpty()) {
            return null;
        }

        if (currentIndex == null) {
            currentIndex = history.size();
            tabCurrentHistoryIndex.put(tab, currentIndex);
        }

        if (currentIndex < history.size() - 1) {
            currentIndex++;
            tabCurrentHistoryIndex.put(tab, currentIndex);
            return history.get(currentIndex);
        } else if (currentIndex >= history.size() - 1) {
            // 超出范围，返回空字符串（清空输入）
            tabCurrentHistoryIndex.put(tab, history.size());
            return "";
        }

        return null;
    }

    /**
     * 获取指定标签页的命令历史
     */
    public List<String> getHistoryForTab(Tab tab) {
        if (tab == null) {
            return Collections.emptyList();
        }

        List<String> history = tabCommandHistory.get(tab);
        return history != null ? new ArrayList<>(history) : Collections.emptyList();
    }

    /**
     * 获取所有标签页的命令历史
     */
    public Map<Tab, List<String>> getAllHistories() {
        Map<Tab, List<String>> copy = new HashMap<>();
        for (Map.Entry<Tab, List<String>> entry : tabCommandHistory.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * 清空指定标签页的命令历史
     */
    public void clearHistoryForTab(Tab tab) {
        if (tab == null) {
            return;
        }

        List<String> history = tabCommandHistory.get(tab);
        if (history != null) {
            history.clear();
        }
        tabCurrentHistoryIndex.put(tab, -1);
    }

    /**
     * 清空所有标签页的命令历史
     */
    public void clearAllHistories() {
        tabCommandHistory.clear();
        tabCurrentHistoryIndex.clear();
    }

    /**
     * 为指定键设置命令历史（用于从云端同步）
     * @param key 历史记录的键
     * @param history 命令历史列表
     */
    public void setHistory(Object key, List<String> history) {
        // 对于从云端同步的场景，我们暂时只处理简单的Object作为键的情况
        // 实际应用中可能需要更复杂的逻辑来重建Tab对象
        if (key instanceof Tab) {
            Tab tab = (Tab) key;
            tabCommandHistory.put(tab, new ArrayList<>(history));
            tabCurrentHistoryIndex.put(tab, history.size());
        } else {
            // 如果不是Tab实例，可以考虑其他处理方式
            // 这里暂时记录警告
            log.warn("Key is not an instance of Tab, unable to set history properly");
        }
    }
}