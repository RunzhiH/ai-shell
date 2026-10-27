/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.util.ControllerUtils;
import javafx.fxml.Initializable;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * 基础控制器类 - 精简版
 * 只提供最基本的服务访问和初始化功能
 */
@Slf4j
public abstract class BaseController implements Initializable {
    
    protected AIShellModuleManager moduleManager;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化模块管理器（用于获取核心服务）
        initializeModuleManager();
    }

    /**
     * 初始化模块管理器
     */
    protected void initializeModuleManager() {
        moduleManager = AIShellModuleManager.getInstance();
    }

    /**
     * 显示信息提示（JavaFX 线程安全）
     */
    protected void showInfoAlert(String message) {
        ControllerUtils.showInfoAlert("提示", message);
    }
    
    /**
     * 显示错误提示（JavaFX 线程安全）
     */
    protected void showErrorAlert(String message) {
        ControllerUtils.showErrorAlert("错误", message);
    }
    
    /**
     * 显示警告提示（JavaFX 线程安全）
     */
    protected void showWarningAlert(String message) {
        ControllerUtils.showWarningAlert("警告", message);
    }
}
