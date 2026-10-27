/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.prefs.Preferences;
import javafx.stage.FileChooser;

/**
 * SSH 设置控制器
 */
@Slf4j
public class SettingsSSHController {
    
    // SSH 默认值
    @FXML private Spinner<Integer> defaultPortSpinner;
    @FXML private TextField defaultUsernameField;
    @FXML private ComboBox<String> authMethodCombo;
    
    // 超时和保活
    @FXML private Spinner<Integer> connectTimeoutSpinner;
    @FXML private Spinner<Integer> readTimeoutSpinner;
    @FXML private Spinner<Integer> keepAliveIntervalSpinner;
    @FXML private CheckBox autoReconnectCheck;
    
    // 密钥管理
    @FXML private TextField privateKeyField;
    @FXML private TextField publicKeyField;
    @FXML private PasswordField keyPassphraseField;
    @FXML private ListView<String> loadedKeysList;
    
    // 代理设置
    @FXML private CheckBox useProxyCheck;
    @FXML private ComboBox<String> proxyTypeCombo;
    @FXML private TextField proxyHostField;
    @FXML private Spinner<Integer> proxyPortSpinner;
    @FXML private CheckBox proxyAuthCheck;
    @FXML private VBox proxyAuthPanel;
    @FXML private TextField proxyUserField;
    @FXML private PasswordField proxyPassField;

    @FXML private CheckBox useJumpHostCheck;
    @FXML private TextField jumpHostField;
    @FXML private Spinner<Integer> jumpPortSpinner;
    @FXML private TextField jumpUsernameField;
    @FXML private PasswordField jumpPasswordField;
    @FXML private TextField jumpPrivateKeyField;
    
    // 高级选项
    @FXML private CheckBox compressionCheck;
    @FXML private CheckBox strictHostKeyCheck;
    @FXML private ComboBox<String> logLevelCombo;
    
    // Preferences 用于存储设置
    private final Preferences prefs = Preferences.userNodeForPackage(SettingsSSHController.class);
    
    /**
     * 初始化方法
     */
    @FXML
    public void initialize() {
        log.info("初始化 SSH 设置页面");
        
        // 加载保存的设置
        loadSettings();
        
        // 监听代理认证复选框
        proxyAuthCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            proxyAuthPanel.setVisible(newVal);
            proxyAuthPanel.setManaged(newVal);
        });
        
        // 监听使用代理复选框
        useProxyCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            setProxyControlsEnabled(newVal);
        });
        useJumpHostCheck.selectedProperty().addListener((obs, oldVal, enabled) -> setJumpControlsEnabled(enabled));
    }
    
    /**
     * 加载设置
     */
    private void loadSettings() {
        // SSH 默认值
        defaultPortSpinner.getValueFactory().setValue(prefs.getInt("ssh.default.port", 22));
        defaultUsernameField.setText(prefs.get("ssh.default.username", "root"));
        authMethodCombo.setValue(prefs.get("ssh.default.auth", "密码认证"));
        
        // 超时和保活
        connectTimeoutSpinner.getValueFactory().setValue(prefs.getInt("ssh.timeout.connect", 30));
        readTimeoutSpinner.getValueFactory().setValue(prefs.getInt("ssh.timeout.read", 60));
        keepAliveIntervalSpinner.getValueFactory().setValue(prefs.getInt("ssh.keepalive.interval", 30));
        autoReconnectCheck.setSelected(prefs.getBoolean("ssh.auto.reconnect", true));
        
        // 密钥管理
        privateKeyField.setText(prefs.get("ssh.key.private", ""));
        publicKeyField.setText(prefs.get("ssh.key.public", ""));
        
        // 代理设置
        useProxyCheck.setSelected(prefs.getBoolean("ssh.proxy.enabled", false));
        proxyTypeCombo.setValue(prefs.get("ssh.proxy.type", "HTTP"));
        proxyHostField.setText(prefs.get("ssh.proxy.host", ""));
        proxyPortSpinner.getValueFactory().setValue(prefs.getInt("ssh.proxy.port", 8080));
        proxyAuthCheck.setSelected(prefs.getBoolean("ssh.proxy.auth", false));
        proxyUserField.setText(prefs.get("ssh.proxy.username", ""));
        String encryptedProxyPassword = prefs.get("ssh.proxy.password", "");
        if (!encryptedProxyPassword.isBlank()) {
            try {
                proxyPassField.setText(com.runzhi.aishell.common.util.PasswordEncryptionUtil
                        .decryptPassword(encryptedProxyPassword));
            } catch (IllegalArgumentException e) {
                proxyPassField.clear();
            }
        }

        useJumpHostCheck.setSelected(prefs.getBoolean("ssh.jump.enabled", false));
        jumpHostField.setText(prefs.get("ssh.jump.host", ""));
        jumpPortSpinner.getValueFactory().setValue(prefs.getInt("ssh.jump.port", 22));
        jumpUsernameField.setText(prefs.get("ssh.jump.username", ""));
        jumpPrivateKeyField.setText(prefs.get("ssh.jump.privateKey", ""));
        String encryptedJumpPassword = prefs.get("ssh.jump.password", "");
        if (!encryptedJumpPassword.isBlank()) {
            try { jumpPasswordField.setText(com.runzhi.aishell.common.util.PasswordEncryptionUtil.decryptPassword(encryptedJumpPassword)); }
            catch (IllegalArgumentException e) { jumpPasswordField.clear(); }
        }
        
        // 高级选项
        compressionCheck.setSelected(prefs.getBoolean("ssh.compression", false));
        strictHostKeyCheck.setSelected(true);
        logLevelCombo.setValue(prefs.get("ssh.log.level", "INFO"));
        
        // 初始化代理控件状态
        boolean useProxy = useProxyCheck.isSelected();
        setProxyControlsEnabled(useProxy);
        setJumpControlsEnabled(useJumpHostCheck.isSelected());
        
        boolean useAuth = proxyAuthCheck.isSelected();
        proxyAuthPanel.setVisible(useAuth);
        proxyAuthPanel.setManaged(useAuth);
        
        log.debug("SSH 设置加载完成");
    }
    
    /**
     * 保存设置
     */
    public void saveSettings() {
        log.info("保存 SSH 设置...");
        
        // SSH 默认值
        prefs.putInt("ssh.default.port", defaultPortSpinner.getValue());
        prefs.put("ssh.default.username", defaultUsernameField.getText());
        prefs.put("ssh.default.auth", authMethodCombo.getValue());
        
        // 超时和保活
        prefs.putInt("ssh.timeout.connect", connectTimeoutSpinner.getValue());
        prefs.putInt("ssh.timeout.read", readTimeoutSpinner.getValue());
        prefs.putInt("ssh.keepalive.interval", keepAliveIntervalSpinner.getValue());
        prefs.putBoolean("ssh.auto.reconnect", autoReconnectCheck.isSelected());
        
        // 密钥管理
        prefs.put("ssh.key.private", privateKeyField.getText());
        prefs.put("ssh.key.public", publicKeyField.getText());
        
        // 代理设置
        prefs.putBoolean("ssh.proxy.enabled", useProxyCheck.isSelected());
        prefs.put("ssh.proxy.type", proxyTypeCombo.getValue());
        prefs.put("ssh.proxy.host", proxyHostField.getText());
        prefs.putInt("ssh.proxy.port", proxyPortSpinner.getValue());
        prefs.putBoolean("ssh.proxy.auth", proxyAuthCheck.isSelected());
        prefs.put("ssh.proxy.username", proxyUserField.getText());
        // 字段被清空时必须移除旧密码：此前只"非空才写"，清空后保存仍用旧密码连接（2026-10-23 审计 P1-27）
        if (!proxyPassField.getText().isBlank()) {
            prefs.put("ssh.proxy.password",
                    com.runzhi.aishell.common.util.PasswordEncryptionUtil.encryptPassword(proxyPassField.getText()));
        } else {
            prefs.remove("ssh.proxy.password");
        }
        prefs.putBoolean("ssh.jump.enabled", useJumpHostCheck.isSelected());
        prefs.put("ssh.jump.host", jumpHostField.getText().trim());
        prefs.putInt("ssh.jump.port", jumpPortSpinner.getValue());
        prefs.put("ssh.jump.username", jumpUsernameField.getText().trim());
        prefs.put("ssh.jump.privateKey", jumpPrivateKeyField.getText().trim());
        if (!jumpPasswordField.getText().isBlank()) {
            prefs.put("ssh.jump.password", com.runzhi.aishell.common.util.PasswordEncryptionUtil.encryptPassword(jumpPasswordField.getText()));
        } else {
            prefs.remove("ssh.jump.password");
        }
        
        // 高级选项
        prefs.putBoolean("ssh.compression", compressionCheck.isSelected());
        prefs.putBoolean("ssh.stricthostkeychecking", true);
        prefs.put("ssh.log.level", logLevelCombo.getValue());
        
        log.info("SSH 设置已保存");
    }
    
    /**
     * 浏览私钥文件
     */
    @FXML
    private void handleBrowsePrivateKey() {
        File file = showFilePicker("选择私钥文件");
        if (file != null) {
            privateKeyField.setText(file.getAbsolutePath());
        }
    }
    
    /**
     * 浏览公钥文件
     */
    @FXML
    private void handleBrowsePublicKey() {
        File file = showFilePicker("选择公钥文件");
        if (file != null) {
            publicKeyField.setText(file.getAbsolutePath());
        }
    }
    
    /**
     * 添加密钥
     */
    @FXML
    private void handleAddKey() {
        File file = showFilePicker("选择密钥文件");
        if (file != null) {
            loadedKeysList.getItems().add(file.getAbsolutePath());
            log.info("添加密钥：{}", file.getAbsolutePath());
        }
    }
    
    /**
     * 移除密钥
     */
    @FXML
    private void handleRemoveKey() {
        int selectedIndex = loadedKeysList.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            loadedKeysList.getItems().remove(selectedIndex);
            log.info("移除选中密钥");
        } else {
            showAlert("提示", "请先选择要移除的密钥");
        }
    }
    
    /**
     * 显示文件选择器
     */
    private File showFilePicker(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SSH 密钥", "*.pem", "*.key", "id_*", "*.pub"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        return chooser.showOpenDialog(privateKeyField.getScene().getWindow());
    }
    
    /**
     * 设置代理控件启用状态
     */
    private void setProxyControlsEnabled(boolean enabled) {
        proxyTypeCombo.setDisable(!enabled);
        proxyHostField.setDisable(!enabled);
        proxyPortSpinner.setDisable(!enabled);
        proxyAuthCheck.setDisable(!enabled);
        proxyUserField.setDisable(!enabled);
        proxyPassField.setDisable(!enabled);
    }

    private void setJumpControlsEnabled(boolean enabled) {
        jumpHostField.setDisable(!enabled);
        jumpPortSpinner.setDisable(!enabled);
        jumpUsernameField.setDisable(!enabled);
        jumpPasswordField.setDisable(!enabled);
        jumpPrivateKeyField.setDisable(!enabled);
    }

    @FXML
    private void handleBrowseJumpPrivateKey() {
        File file = showFilePicker("选择跳板机私钥");
        if (file != null) jumpPrivateKeyField.setText(file.getAbsolutePath());
    }
    
    /**
     * 显示提示对话框
     */
    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
