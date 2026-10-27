/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class NewConnectionConfigController {

    @FXML
    private TextField profileNameField;

    @FXML
    private TextField hostField;

    @FXML
    private TextField portField;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private TextField privateKeyPathField;

    // 编辑模式：正在修改的原始连接参数。为 null 表示新建模式
    private ConnectionParams editingParams;

    // 引用SSH服务实例
    private SSHServiceImpl sshService;

    // 对话框阶段引用
    private Stage dialogStage;

    // 刷新回调函数
    private Runnable refreshCallback;

    // 设置SSH服务实例
    public void setSshService(SSHServiceImpl sshService) {
        this.sshService = sshService;
    }

    // 设置对话框阶段
    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    // 设置刷新回调
    public void setRefreshCallback(Runnable refreshCallback) {
        this.refreshCallback = refreshCallback;
    }

    @FXML
    private void handleSave() {
        try {
            // 获取用户输入
            String profileName = profileNameField.getText().trim();
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();
            String username = usernameField.getText().trim();
            String password = passwordField.getText().trim();
            String privateKeyPath = privateKeyPathField.getText().trim();

            // 验证输入
            if (profileName.isEmpty()) {
                showAlert("配置名称不能为空");
                return;
            }

            if (host.isEmpty()) {
                showAlert("主机地址不能为空");
                return;
            }

            int port = com.runzhi.aishell.common.config.AppConstants.DEFAULT_SSH_PORT;
            if (!portStr.isEmpty()) {
                try {
                    port = Integer.parseInt(portStr);
                    if (port < 1 || port > 65535) {
                        showAlert("端口号必须在1-65535之间");
                        return;
                    }
                } catch (NumberFormatException e) {
                    showAlert("端口号必须是数字");
                    return;
                }
            }

            if (username.isEmpty()) {
                showAlert("用户名不能为空");
                return;
            }

            // 编辑模式下密码留空表示沿用原密码；新建模式下允许留空（部分服务器免密/仅用密钥）
            String effectivePassword = password;
            if (editingParams != null && password.isEmpty()) {
                effectivePassword = editingParams.getPassword();
            }

            // 保存连接配置
            if (sshService instanceof com.runzhi.aishell.ssh.impl.SSHServiceImpl) {
                com.runzhi.aishell.ssh.impl.SSHServiceImpl sshServiceImpl =
                        (com.runzhi.aishell.ssh.impl.SSHServiceImpl) sshService;
                if (editingParams != null) {
                    // 【修复】编辑模式：更新现有配置，而不是新增一条
                    ConnectionParams updated = new ConnectionParams();
                    // 编辑模式必须回写 profileName，否则更新后连接名称丢失（2026-10-23 审计 P1-6）
                    updated.setProfileName(profileName);
                    updated.setHost(host);
                    updated.setPort(port);
                    updated.setUsername(username);
                    updated.setPassword(effectivePassword);
                    updated.setPrivateKeyPath(privateKeyPath);
                    com.runzhi.aishell.common.config.ConfigManager.getInstance()
                            .updateConnectionConfig(editingParams, updated);
                } else {
                    sshServiceImpl.saveConnectionProfile(profileName, host, port, username,
                            effectivePassword, privateKeyPath);
                }
            }

            // 关闭对话框
            if (dialogStage != null) {
                dialogStage.close();
            }

            showAlert("连接配置保存成功！");

            // 调用刷新回调
            if (refreshCallback != null) {
                refreshCallback.run();
            }

        } catch (Exception e) {
            showAlert("保存连接配置时发生错误: " + e.getMessage());
        }
    }

    @FXML
    private void handleCancel() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    private void showAlert(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * 加载现有配置到表单（进入编辑模式）
     */
    public void loadExistingConfig(ConnectionParams params) {
        if (params != null) {
            this.editingParams = params;
            // 回填用户起的名字，而不是用 host:port 覆盖掉
            profileNameField.setText(params.getProfileName() != null
                    ? params.getProfileName()
                    : com.runzhi.aishell.ssh.SSHConnectionManager.buildDisplayName(params));
            hostField.setText(params.getHost());
            portField.setText(String.valueOf(params.getPort()));
            usernameField.setText(params.getUsername());
            // 【修复】回填私钥路径，方便修改
            privateKeyPathField.setText(params.getPrivateKeyPath() == null ? "" : params.getPrivateKeyPath());
            // 密码出于安全不回填，用户留空即表示沿用原密码
            passwordField.setPromptText("留空表示沿用原密码");
            passwordField.setText("");
        }
    }

    @FXML
    private void browsePrivateKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择私钥文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("私钥文件", "*"));
        if (privateKeyPathField.getText() != null && !privateKeyPathField.getText().isBlank()) {
            java.io.File cur = new java.io.File(privateKeyPathField.getText());
            if (cur.getParentFile() != null && cur.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(cur.getParentFile());
            }
        }
        java.io.File selected = chooser.showOpenDialog(dialogStage);
        if (selected != null) {
            privateKeyPathField.setText(selected.getAbsolutePath());
        }
    }
}