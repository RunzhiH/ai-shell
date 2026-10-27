/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service;

import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import org.fxmisc.richtext.InlineCssTextArea;

/**
 * 终端 UI 服务接口
 * 负责终端的 UI 交互、样式、快捷键等
 */
public interface TerminalUIService {

    /**
     * 设置 Tab 快捷键
     * @param tab 终端标签页
     * @param input 输入框
     */
    void setupTabShortcuts(Tab tab, TextField input);

    /**
     * 设置 Tab 右键菜单
     * @param terminalTabPane 终端标签页容器
     */
    void setupTabContextMenu(javafx.scene.control.TabPane terminalTabPane);

    /**
     * 设置键盘快捷键
     * @param terminalTabPane 终端标签页容器
     */
    void setupKeyboardShortcuts(javafx.scene.control.TabPane terminalTabPane);

    /**
     * 解析 ANSI 颜色代码并应用到 InlineCssTextArea
     * @param textArea 文本区域
     * @param text 包含 ANSI 代码的文本
     */
    void applyAnsiCodesToText(InlineCssTextArea textArea, String text);

    /**
     * 根据 ANSI 代码返回对应的 CSS 样式
     * @param ansiCode ANSI 代码
     * @param defaultStyle 默认样式
     * @return CSS 样式
     */
    String getStyleForAnsiCode(String ansiCode, String defaultStyle);

    /**
     * 为标签页集成文件管理器
     * @param tab 终端标签页
     */
    void integrateFileManager(Tab tab);

    /**
     * 获取终端和文件管理器的组合界面
     * @return 组合界面
     */
    javafx.scene.Parent getTerminalAndFileManagerView();

    /**
     * 为指定的 Tab 创建终端和文件管理器的组合界面
     * @param tabId 标签页 ID
     * @return 组合界面
     */
    javafx.scene.Parent createTerminalAndFileManagerViewForTab(String tabId);

    /**
     * 设置输入模式选择框
     * @param inputModeChoiceBox 输入模式选择框
     * @param commandInput 命令输入框
     */
    void setupInputModeChoiceBox(javafx.scene.control.ChoiceBox<String> inputModeChoiceBox, TextField commandInput);

    /**
     * 测试 ANSI 颜色代码处理功能
     * @param textArea 文本区域
     */
    void testAnsiColors(InlineCssTextArea textArea);

    /**
     * 设置监控面板控制器
     * @param monitorPanelController 监控面板控制器
     */
    void setMonitorPanelController(com.runzhi.aishell.controller.MonitorPanelController monitorPanelController);

    /**
     * 刷新文件列表
     */
    void refreshFileList();

    /**
     * 设置文件管理器面板控制器
     * @param fileManagerPanelController 文件管理器面板控制器
     */
    void setFileManagerPanelController(com.runzhi.aishell.controller.FileManagerPanelController fileManagerPanelController);
    
    /**
     * 清屏
     * @param tab 终端标签页
     */
    void clearScreen(Tab tab);
    
    /**
     * 清空终端输出
     * @param tab 终端标签页
     */
    void clearTerminalOutput(Tab tab);
    
    /**
     * 复制终端输出
     * @param tab 终端标签页
     */
    void copyTerminalOutput(Tab tab);
    
    /**
     * 保存终端输出到文件
     * @param tab 终端标签页
     */
    void saveTerminalOutputToFile(Tab tab);
    
    /**
     * 粘贴到终端
     * @param tab 终端标签页
     */
    void pasteToTerminal(Tab tab);
}