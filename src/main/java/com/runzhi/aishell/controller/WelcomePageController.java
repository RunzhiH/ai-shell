/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.common.ai.AIShellModuleManager;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * 欢迎页面控制器 — 提供新建连接和 AI 助手两个快速入口。
 */
@Slf4j
public class WelcomePageController extends BaseController implements Initializable {

    @FXML
    private VBox welcomePage;

    private AIShellModuleManager moduleManager;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        moduleManager = AIShellModuleManager.getInstance();
        log.info("欢迎页面已初始化");
    }

    @FXML
    private void newConnection() {
        log.info("用户点击新建连接");
        if (moduleManager != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().put("show_connection_list", true, 5000);
        }
    }

    @FXML
    private void openAIAssistant() {
        log.info("打开 AI 助手");
        var adapter = AIConfigManager.getInstance().getCurrentAIAdapter();
        if (adapter == null || !adapter.isConfigured()) {
            SettingsDialogController.showDialog(
                    welcomePage != null && welcomePage.getScene() != null
                            ? welcomePage.getScene().getWindow() : null, "ai");
            return;
        }
        if (moduleManager != null) {
            moduleManager.getNavigationManager().openInNewWindow("ai-assistant-panel.fxml", "AI 助手");
        }
    }
}
