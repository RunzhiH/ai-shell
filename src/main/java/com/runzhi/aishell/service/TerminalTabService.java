/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service;

import com.runzhi.aishell.ssh.domain.ConnectionParams;
import javafx.scene.control.Tab;

/**
 * 终端标签页管理服务接口
 * 负责终端 Tab 的创建、初始化、关闭等生命周期管理
 */
public interface TerminalTabService {

    /**
     * 创建新的终端 Tab（无连接）
     */
    void createNewTerminalTab();

    /**
     * 使用指定的连接参数创建新的终端 Tab
     * @param params SSH 连接参数
     */
    void createNewTerminalTabWithParams(ConnectionParams params);

    /**
     * 从参数管理器获取连接参数并创建新的终端 Tab
     */
    void createNewTerminalTabWithParamsFromManager();

    /**
     * 为每个 Tab 设置独立终端
     * @param tab 终端标签页
     * @param tabId 标签页 ID
     */
    void setupTabTerminal(Tab tab, String tabId);

    /**
     * 初始化终端面板
     * @param terminalTabPane 终端标签页容器
     */
    void initializeTerminalPanel(javafx.scene.control.TabPane terminalTabPane);

    /**
     * 设置 Tab 关闭监听器
     * @param terminalTabPane 终端标签页容器
     */
    void setupTabCloseListener(javafx.scene.control.TabPane terminalTabPane);

    /**
     * 设置双击新建终端 Tab 功能
     * @param terminalTabPane 终端标签页容器
     * @param createNewTerminalTabRunnable 创建新 Tab 的 Runnable
     */
    void setupDoubleClickNewTab(javafx.scene.control.TabPane terminalTabPane, Runnable createNewTerminalTabRunnable);

    /**
     * 获取标签页 ID
     * @param tab 终端标签页
     * @return 标签页 ID
     */
    String getTabId(Tab tab);

    /**
     * 清空终端输出
     * @param tab 终端标签页
     */
    void clearTerminalOutput(Tab tab);

    /**
     * 复制终端输出到剪贴板
     * @param tab 终端标签页
     */
    void copyTerminalOutput(Tab tab);

    /**
     * 保存终端输出到文件
     * @param tab 终端标签页
     */
    void saveTerminalOutputToFile(Tab tab);
    
    /**
     * 获取 Tab 到输出区域的映射
     * @return 映射表
     */
    java.util.Map<javafx.scene.control.Tab, org.fxmisc.richtext.InlineCssTextArea> getTabToOutput();
    
    /**
     * 获取 Tab 到输入框的映射
     * @return 映射表
     */
    java.util.Map<javafx.scene.control.Tab, javafx.scene.control.TextField> getTabToInput();
}