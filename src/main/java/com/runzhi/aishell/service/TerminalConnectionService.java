/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service;

import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import org.fxmisc.richtext.InlineCssTextArea;

/**
 * 终端连接服务接口
 * 负责 SSH 连接的建立、管理、重连等
 */
public interface TerminalConnectionService {

    /**
     * 为标签页建立 SSH 连接，确保输出回调已注册
     * @param tabId 标签页 ID
     * @param params 连接参数
     * @param tab 终端标签页
     */
    void establishSSHConnectionForTab(String tabId, ConnectionParams params, Tab tab);

    /**
     * 尝试重新连接到指定的 Tab
     * @param tabId 标签页 ID
     * @param output 输出区域
     */
    void reconnectTab(String tabId, InlineCssTextArea output);

    /**
     * 处理断开连接的逻辑
     */
    void handleDisconnection();

    /**
     * 设置 TerminalService 的依赖项
     * @param sshService SSH 服务
     */
    void setDependencies(SSHService sshService);

    /**
     * 获取 SSH 服务
     * @return SSH 服务实例
     */
    SSHService getSshService();

    /**
     * 显示连接选择对话框
     * @return 连接参数
     */
    ConnectionParams showConnectionSelectionDialog();

    /**
     * 显示连接管理对话框
     * @param comboBox 组合框
     * @return 连接参数
     */
    ConnectionParams showConnectionManagementDialog(ComboBox<String> comboBox);

    /**
     * 刷新连接配置文件
     * @param comboBox 组合框
     */
    void refreshConnectionProfiles(ComboBox<String> comboBox);

    /**
     * 初始化 AI 适配器
     */
    void initializeAIAdapter();

    /**
     * 检查指定 Tab 的连接健康状态
     * @param tabId 标签页 ID
     */
    void checkConnectionHealth(String tabId);

    /**
     * 获取指定 Tab 的连接健康状态
     * @param tabId 标签页 ID
     * @return 是否健康
     */
    boolean isConnectionHealthy(String tabId);
    
    /**
     * 尝试重新连接到特定的 Tab
     * @param tab 终端标签页
     */
    void attemptReconnection(javafx.scene.control.Tab tab);
}
