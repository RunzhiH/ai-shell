/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service;

import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import org.fxmisc.richtext.InlineCssTextArea;

/**
 * 终端命令执行服务接口
 * 负责命令的执行、处理、历史管理等
 */
public interface TerminalCommandService {

    /**
     * 在指定 Tab 中执行命令（从输入框）
     * @param tab 终端标签页
     * @param input 输入框控件
     * @param modeComboBox 模式选择框
     */
    void executeCurrentCommandInTab(Tab tab, TextField input, ChoiceBox<String> modeComboBox);

    /**
     * 在指定 Tab 中执行命令（从输出区域调用）
     * @param tab 终端标签页
     * @param command 命令字符串
     */
    void executeCommandInTabFromOutput(Tab tab, String command);

    /**
     * 初始化 Tab 的命令历史
     * @param tab 终端标签页
     */
    void initializeTabCommandHistory(Tab tab);

    /**
     * 在当前终端中直接处理 AI 命令
     * @param tab 终端标签页
     * @param command AI 命令
     */
    void executeAICommandDirectly(Tab tab, String command);

    /**
     * 处理 AI 终端命令
     * @param tab 终端标签页
     * @param command 命令
     */
    void processAITerminalCommand(Tab tab, String command);

    /** 取消指定终端当前正在处理的 AI 请求。 */
    void cancelAIRequest(Tab tab);

    /**
     * 在 Tab 中执行命令
     * @param tab 终端标签页
     * @param command 命令
     */
    void executeCommandInTab(Tab tab, String command);

    /**
     * 处理 cd 命令
     * @param tab 终端标签页
     * @param command cd 命令
     */
    void handleCdCommandForTab(Tab tab, String command);

    /**
     * 添加命令到历史记录
     * @param tab 终端标签页
     * @param command 命令
     */
    void addCommandToHistory(Tab tab, String command);

    /**
     * 设置命令历史导航
     * @param commandInput 命令输入框
     * @param tab 终端标签页
     */
    void setupCommandHistoryNavigation(TextField commandInput, Tab tab);

    /**
     * 设置输出区域的命令输入功能
     * @param outputArea 输出区域
     * @param tabId 标签页 ID
     * @param tab 终端标签页
     */
    void setupOutputAreaCommandInput(InlineCssTextArea outputArea, String tabId, Tab tab);

    /**
     * 获取当前工作目录
     * @param tab 终端标签页
     * @return 当前工作目录
     */
    String getCurrentWorkingDirectory(Tab tab);

    /**
     * 执行 shell 命令
     * @param tab 终端标签页
     * @param command 命令
     */
    void executeShellCommand(Tab tab, String command);

    /**
     * 启用高级 shell 功能
     * @param tab 终端标签页
     */
    void enableAdvancedShellFeatures(Tab tab);

    /**
     * 设置高级键盘快捷键
     * @param tab 终端标签页
     */
    void setupAdvancedKeyboardShortcuts(Tab tab);

    /**
     * 尝试重新连接到特定的 Tab
     * @param tab 终端标签页
     */
    void attemptReconnection(Tab tab);

    /**
     * 执行粘贴命令
     * @param tab 终端标签页
     */
    void pasteToTerminal(Tab tab);

    /**
     * 执行清屏命令
     * @param tab 终端标签页
     */
    void clearScreen(Tab tab);
    
    /**
     * 设置 Tab 快捷键
     * @param tab 终端标签页
     * @param input 输入框
     */
    void setupTabShortcuts(Tab tab, TextField input);
}