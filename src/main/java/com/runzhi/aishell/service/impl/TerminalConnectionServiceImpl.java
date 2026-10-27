/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service.impl;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.service.TerminalConnectionService;
import com.runzhi.aishell.service.TerminalUIService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;


import java.util.Map;

/**
 * 终端连接服务实现
 * 负责 SSH 连接的建立、管理、重连等
 */
@Slf4j
public class TerminalConnectionServiceImpl implements TerminalConnectionService {

    private SSHService sshService;
    private AIAdapter aiAdapter;
    /**
     * -- GETTER --
     *  获取 TerminalUIService
     */
    @Getter
    private TerminalUIService terminalUIService; // 用于设置当前 Tab ID
    
    /**
     * 构造函数注入 SSH 服务
     * 直接注入 SSH 服务，避免死锁
     */
    public TerminalConnectionServiceImpl(SSHService sshService) {
        if (sshService == null) {
            throw new IllegalArgumentException("SSHService cannot be null");
        }
        this.sshService = sshService;
        log.info("TerminalConnectionServiceImpl initialized with SSHService instance: {}", sshService.getClass().getSimpleName());
    }
    
    /**
     * @deprecated 使用构造函数注入，不再需要此方法
     * 保留此方法仅用于向后兼容，新代码不应调用
     */
    @Deprecated
    @Override
    public void setDependencies(SSHService sshService) {
        log.warn("setDependencies called - setting SSHService, but prefer constructor injection");
        if (sshService != null) {
            this.sshService = sshService;
        }
    }
    
    /**
     * 设置 TerminalUIService (用于设置当前 Tab ID)
     */
    public void setTerminalUIService(TerminalUIService terminalUIService) {
        this.terminalUIService = terminalUIService;
        log.debug("TerminalUIService injected into TerminalConnectionServiceImpl");
    }

    @Override
    public void establishSSHConnectionForTab(String tabId, ConnectionParams params, javafx.scene.control.Tab tab) {
        log.debug("Establishing SSH connection for tab {}: {}:{}" , tabId, params.getHost(), params.getPort());
        
        // 获取终端输出区域
        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        
        // 在终端显示连接中信息
        if (outputArea != null) {
            outputArea.appendText("正在连接到 " + params.getHost() + ":" + params.getPort() + "...\n");
        }
        
        // 在后台线程中建立连接，避免阻塞UI
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                // 【关键】先创建输出回调
                com.runzhi.aishell.ssh.SSHConnectionManager.ShellOutputCallback callback = null;
                if (outputArea != null) {
                    callback = com.runzhi.aishell.terminal.handler.TerminalOutputHandler.createShellStyleOutputCallback(outputArea);
                    log.info("Created output callback for tab: {}", tabId);
                }
                
                // 使用新方法：在连接之前注册回调，确保不会丢失早期输出
                com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.createTabConnectionWithCallback(
                    tabId,
                    params.getHost(),
                    params.getPort(),
                    params.getUsername(),
                    params.getDecryptedPassword(),
                    params.getPrivateKeyPath(),
                    callback
                );
                
                if (connection != null && connection.isConnected()) {
                    log.info("SSH connection established successfully for tab: {}", tabId);
                    sshService.syncRemoteDirectoryForTab(tabId);
                    final String remoteHome = sshService.getCurrentWorkingDirectoryForTab(tabId);
                    
                    // 在UI线程显示连接成功信息
                    Platform.runLater(() -> {
                        try {
                            com.runzhi.aishell.terminal.InteractiveTerminalView.mount(
                                    tab, connection.getSession(),
                                    params.getUsername() + "@" + params.getHost(),
                                    directorySynchronizer(tabId, tab, remoteHome));
                            com.runzhi.aishell.terminal.InteractiveTerminalView
                                    .updateFilePath(tab, remoteHome);
                            // 连接成功：文件管理器若可见则加载；不可见则等抽屉展开
                            refreshFileManagerForTab(tab, tabId);
                            com.runzhi.aishell.common.util.ParameterManager.getInstance()
                                    .put("current_active_tab", tabId, 5000);
                            connection.releaseLegacyInteractiveShell();
                            // 刷新连接信息栏（主机/用户/状态点），此前该栏永远不显示（2026-10-23 审计 P1-2）
                            Object tabController = tab.getProperties().get("terminalTabController");
                            if (tabController instanceof com.runzhi.aishell.controller.TerminalTabController ttc) {
                                ttc.updateConnectionInfoBar(connection);
                            }
                            // 自动重连成功后旧 widget 仍绑定在已断开的会话上，需在恢复时重新挂接终端。
                            connection.addConnectionStateListener(reconnectRemountListener(tabId, tab, params));
                            log.info("Interactive JediTermFX mounted for tab: {}", tabId);
                        } catch (Exception terminalError) {
                            log.error("Failed to mount interactive terminal; keeping legacy view: {}",
                                    terminalError.getMessage(), terminalError);
                            if (outputArea != null) {
                                outputArea.appendText("连接到服务器：" + params.getHost() + ":" + params.getPort()
                                        + " (用户：" + params.getUsername() + ")\n");
                                outputArea.appendText("交互终端初始化失败，已切换兼容模式："
                                        + terminalError.getMessage() + "\n\n");
                            }
                        }
                    });
                } else {
                    log.error("Failed to establish SSH connection for tab: {}", tabId);
                    String connectionError = sshService.getLastConnectionError(tabId);
                    Platform.runLater(() -> {
                        if (outputArea != null) {
                            outputArea.appendText("SSH 连接失败：" +
                                    (connectionError == null ? "请检查网络连接或服务器状态" : connectionError) + "\n");
                        }
                        if (isAuthenticationFailure(connectionError)) {
                            promptForPasswordAndRetry(tabId, params, tab, outputArea);
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Error establishing SSH connection for tab: {}", tabId, e);
                Platform.runLater(() -> {
                    if (outputArea != null) {
                        outputArea.appendText("连接错误：" + e.getMessage() + "\n");
                    }
                    // 缺凭据（密码和私钥都为空）不是程序错误，应让用户当场输入密码重试，
                    // 而不是只留一行错误文字
                    if (isMissingCredentials(e)) {
                        promptForPasswordAndRetry(tabId, params, tab, outputArea, true);
                    }
                });
            }
        });
    }

    /** 终端 OSC 上报目录后，同步到标签的远程路径与文件面板。 */
    private java.util.function.Consumer<String> directorySynchronizer(String tabId,
                                                                      javafx.scene.control.Tab tab,
                                                                      String remoteHome) {
        return directory -> {
            String resolved = directory;
            if (directory.startsWith("~") && remoteHome != null && remoteHome.startsWith("/")) {
                resolved = remoteHome + directory.substring(1);
            }
            sshService.setCurrentWorkingDirectoryForTab(tabId, resolved);
            com.runzhi.aishell.terminal.InteractiveTerminalView.updateFilePath(tab, resolved);
            // 文件列表刷新由 FileManagerPanelController 防抖处理，此处不直接刷
        };
    }

    /** 连接建立/恢复后刷新该标签的文件面板：可见则加载，不可见则等抽屉展开。 */
    private void refreshFileManagerForTab(javafx.scene.control.Tab tab, String tabId) {
        var fmController = tab.getContent() != null ? findFileManagerController(tab.getContent()) : null;
        if (fmController != null) {
            fmController.setPanelVisible(fmController.isPanelVisible());
        } else {
            AIShellModuleManager.getInstance().refreshFileListForTab(tabId);
        }
    }

    /** 连接恢复事件监听器：自动重连成功后回到 FX 线程重挂终端。 */
    private com.runzhi.aishell.ssh.ConnectionStateListener reconnectRemountListener(
            String tabId, javafx.scene.control.Tab tab, ConnectionParams params) {
        return new com.runzhi.aishell.ssh.ConnectionStateListener() {
            @Override
            public void onConnected(String id) {
                Platform.runLater(() -> remountAfterReconnect(tabId, tab, params));
            }

            @Override
            public void onDisconnected(String id) { }
        };
    }

    /**
     * 重连成功后重挂终端与文件上下文。旧 widget 绑定在已断开的会话上，
     * 不重挂则用户看到的仍是死终端（命令只能退化为执行通道）。
     */
    private void remountAfterReconnect(String tabId, javafx.scene.control.Tab tab, ConnectionParams params) {
        com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
        if (connection == null || !connection.isConnected()) return;
        if (tab == null || !tab.getProperties().containsKey("interactiveTerminalWidget")) return;
        try {
            sshService.syncRemoteDirectoryForTab(tabId);
            String remoteHome = sshService.getCurrentWorkingDirectoryForTab(tabId);
            com.runzhi.aishell.terminal.InteractiveTerminalView.mount(
                    tab, connection.getSession(), params.getUsername() + "@" + params.getHost(),
                    directorySynchronizer(tabId, tab, remoteHome));
            // connect() 在重连时又开启了 legacy shell 通道；JediTerm 已在自己的通道上重挂，
            // 必须释放 legacy 通道，否则每次重连都泄漏一个 ChannelShell。
            connection.releaseLegacyInteractiveShell();
            com.runzhi.aishell.terminal.InteractiveTerminalView.updateFilePath(tab, remoteHome);
            refreshFileManagerForTab(tab, tabId);
            com.runzhi.aishell.terminal.InteractiveTerminalView.writeFeedback(tab, "连接已恢复，终端已重新挂接。");
            log.info("Terminal re-mounted after reconnection for tab: {}", tabId);
        } catch (Exception e) {
            log.warn("Failed to re-mount terminal after reconnection for tab {}: {}", tabId, e.getMessage(), e);
            com.runzhi.aishell.terminal.InteractiveTerminalView.writeFeedback(tab,
                    "连接已恢复，但终端重挂失败：" + e.getMessage());
        }
    }

    /** 通过终端标签服务按 id 查找标签（用于重连后重挂终端）。 */
    private javafx.scene.control.Tab findTabById(String tabId) {
        try {
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalTabService tabService = moduleManager.getTerminalTabService();
                if (tabService instanceof TerminalTabServiceImpl impl) {
                    for (javafx.scene.control.Tab tab : impl.getTabToOutput().keySet()) {
                        if (tabId.equals(tab.getId())) return tab;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not find tab by id {}: {}", tabId, e.getMessage());
        }
        return null;
    }

    private boolean isAuthenticationFailure(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String normalized = errorMessage.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("auth fail")
                || normalized.contains("authentication")
                || normalized.contains("password")
                || normalized.contains("认证失败")
                || normalized.contains("密码错误");
    }

    /**
     * SSHConnectionManager 在密码与私钥都为空时抛 IllegalArgumentException，
     * 属于可当场补救的凭据缺失，不是程序错误
     */
    private boolean isMissingCredentials(Throwable e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage();
        return message.contains("必须提供密码或私钥路径");
    }

    private void promptForPasswordAndRetry(String tabId, ConnectionParams params,
                                           javafx.scene.control.Tab tab,
                                           InlineCssTextArea outputArea) {
        promptForPasswordAndRetry(tabId, params, tab, outputArea, false);
    }

    private void promptForPasswordAndRetry(String tabId, ConnectionParams params,
                                           javafx.scene.control.Tab tab,
                                           InlineCssTextArea outputArea,
                                           boolean missingCredentials) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(missingCredentials ? "需要密码" : "SSH 认证失败");
        dialog.setHeaderText(missingCredentials
                ? "请输入 " + params.getUsername() + "@" + params.getHost() + " 的密码"
                : "无法登录 " + params.getUsername() + "@" + params.getHost());

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(missingCredentials ? "输入密码" : "重新输入密码");
        passwordField.setPrefColumnCount(28);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label(missingCredentials
                        ? "该连接配置未保存密码，请输入密码后连接："
                        : "密码可能不正确，请重新输入："), passwordField));

        ButtonType retryButton = new ButtonType("重新连接", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(retryButton, ButtonType.CANCEL);
        javafx.scene.Node retryNode = dialog.getDialogPane().lookupButton(retryButton);
        retryNode.disableProperty().bind(passwordField.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == retryButton ? passwordField.getText() : null);

        dialog.showAndWait().ifPresent(newPassword -> {
            params.setPassword(newPassword);
            // 持久化新密码到连接配置，否则下次连接仍用旧密码（加密由 setPassword 完成）
            try {
                com.runzhi.aishell.common.config.ConfigManager.getInstance()
                        .updateConnectionConfig(params, params);
            } catch (Exception saveError) {
                log.error("Failed to persist updated password for {}@{}",
                        params.getUsername(), params.getHost(), saveError);
            }
            if (outputArea != null) {
                outputArea.appendText("正在使用新密码重新连接...\n");
            }
            establishSSHConnectionForTab(tabId, params, tab);
        });
    }
    
    /**
     * 获取 Tab 对应的输出区域
     */
    private InlineCssTextArea getOutputAreaForTab(javafx.scene.control.Tab tab) {
        if (tab == null) {
            return null;
        }
        
        // 方案1：从 TerminalTabService 获取输出区域映射
        try {
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalTabService tabService = moduleManager.getTerminalTabService();
                if (tabService instanceof TerminalTabServiceImpl) {
                    Map<javafx.scene.control.Tab, InlineCssTextArea> tabToOutput = 
                        ((TerminalTabServiceImpl) tabService).getTabToOutput();
                    if (tabToOutput != null) {
                        InlineCssTextArea output = tabToOutput.get(tab);
                        if (output != null) {
                            return output;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not get output area from TerminalTabService: {}", e.getMessage());
        }
        
        // 方案2：从 Tab 内容中查找输出区域
        try {
            javafx.scene.Node content = tab.getContent();
            if (content instanceof javafx.scene.Parent) {
                InlineCssTextArea found = findOutputArea((javafx.scene.Parent) content);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception e) {
            log.debug("Could not find output area in tab content: {}", tab.getId());
        }
        
        return null;
    }
    
    /**
     * 递归查找输出区域
     */
    private InlineCssTextArea findOutputArea(javafx.scene.Parent parent) {
        for (javafx.scene.Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof InlineCssTextArea) {
                return (InlineCssTextArea) node;
            }
            if (node instanceof javafx.scene.Parent) {
                InlineCssTextArea found = findOutputArea((javafx.scene.Parent) node);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Override
    public void reconnectTab(String tabId, InlineCssTextArea output) {
        log.info("Attempting to reconnect tab: {}", tabId);
        
        if (tabId == null || output == null) {
            log.warn("Tab ID or output is null, cannot reconnect");
            return;
        }
        
        ConnectionParams params = sshService.getTabConnectionParams(tabId);
        if (params == null) {
            output.appendText("未找到连接参数，无法重连\n");
            log.warn("No connection params found for tab: {}", tabId);
            return;
        }

        output.appendText("正在重新连接...\n");
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                // 只替换当前 Tab 的连接，不能影响其他已打开的终端。
                sshService.removeTabConnection(tabId);
                var callback = com.runzhi.aishell.terminal.handler.TerminalOutputHandler
                        .createShellStyleOutputCallback(output);
                var connection = sshService.createTabConnectionWithCallback(
                        tabId, params.getHost(), params.getPort(), params.getUsername(),
                        params.getDecryptedPassword(), params.getPrivateKeyPath(), callback);
                boolean success = connection != null && connection.isConnected();
                Platform.runLater(() -> {
                    if (success) {
                        output.appendText("重新连接成功!\n");
                        // 手动重连同样需要重挂终端：旧 widget 仍绑定在已断开的会话上
                        javafx.scene.control.Tab tab = findTabById(tabId);
                        if (tab != null) {
                            remountAfterReconnect(tabId, tab, params);
                        }
                        log.info("Tab {} reconnected successfully", tabId);
                    } else {
                        String error = sshService.getLastConnectionError(tabId);
                        output.appendText("重新连接失败：" +
                                (error == null ? "请检查网络连接或服务器状态" : error) + "\n");
                        log.warn("Failed to reconnect tab: {}", tabId);
                    }
                });
            } catch (Exception e) {
                log.error("Error during reconnection for tab {}: {}", tabId, e.getMessage(), e);
                Platform.runLater(() -> output.appendText("重新连接时出错：" + e.getMessage() + "\n"));
            }
        });
    }

    @Override
    public void handleDisconnection() {
        log.info("Handling disconnection");
        
        if (sshService != null) {
            sshService.disconnect();
        }
    }

    @Override
    public SSHService getSshService() {
        return sshService;
    }

    @Override
    public ConnectionParams showConnectionSelectionDialog() {
        log.info("Showing connection selection dialog");
        
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ConnectionParams> resultRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable showDialog = () -> {
            Dialog<ConnectionParams> dialog = new Dialog<>();
            dialog.setTitle("SSH 连接管理");
            dialog.setHeaderText("选择现有连接或创建新连接");

            ButtonType connectButtonType = new ButtonType("连接", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(connectButtonType, ButtonType.CANCEL);

            ComboBox<String> profileComboBox = new ComboBox<>();
            profileComboBox.setPromptText("选择连接配置...");
            refreshConnectionProfiles(profileComboBox);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(10));

            TextField hostField = new TextField();
            TextField portField = new TextField();
            TextField usernameField = new TextField();
            PasswordField passwordField = new PasswordField();
            TextField keyPathField = new TextField();

            grid.add(new Label("主机:"), 0, 0);
            grid.add(hostField, 1, 0);
            grid.add(new Label("端口:"), 0, 1);
            grid.add(portField, 1, 1);
            grid.add(new Label("用户名:"), 0, 2);
            grid.add(usernameField, 1, 2);
            grid.add(new Label("密码:"), 0, 3);
            grid.add(passwordField, 1, 3);
            grid.add(new Label("私钥路径:"), 0, 4);
            grid.add(keyPathField, 1, 4);

            VBox vbox = new VBox(10);
            vbox.getChildren().addAll(new Label("选择连接配置:"), profileComboBox, grid);
            dialog.getDialogPane().setContent(vbox);

            profileComboBox.setOnAction(e -> {
                String selectedProfile = profileComboBox.getValue();
                if (selectedProfile != null && sshService != null) {
                    ConnectionParams savedParams = sshService.getConnectionProfile(selectedProfile);
                    if (savedParams != null) {
                        hostField.setText(savedParams.getHost() != null ? savedParams.getHost() : "");
                        portField.setText(String.valueOf(savedParams.getPort()));
                        usernameField.setText(savedParams.getUsername() != null ? savedParams.getUsername() : "");
                    }
                }
            });

            dialog.setResultConverter(dialogButton -> {
                if (dialogButton == connectButtonType) {
                    try {
                        ConnectionParams params = new ConnectionParams();
                        params.setHost(hostField.getText().isEmpty() ? "localhost" : hostField.getText());
                        params.setPort(portField.getText().isEmpty() ? 22 : Integer.parseInt(portField.getText()));
                        params.setUsername(usernameField.getText().isEmpty() ? "username" : usernameField.getText());
                        params.setPassword(passwordField.getText());
                        params.setPrivateKeyPath(keyPathField.getText().isEmpty() ? null : keyPathField.getText());
                        return params;
                    } catch (NumberFormatException ex) {
                        log.error("端口号必须是数字", ex);
                        return null;
                    }
                }
                return null;
            });

            try {
                resultRef.set(dialog.showAndWait().orElse(null));
            } finally {
                latch.countDown();
            }
        };

        if (Platform.isFxApplicationThread()) {
            showDialog.run();
            return resultRef.get();
        }
        Platform.runLater(showDialog);

        try {
            if (latch.await(0, java.util.concurrent.TimeUnit.SECONDS)) {
                return resultRef.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public ConnectionParams showConnectionManagementDialog(javafx.scene.control.ComboBox<String> comboBox) {
        log.info("Showing connection management dialog");
        
        // 使用 CountDownLatch 等待异步操作完成
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ConnectionParams> resultRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable showDialog = () -> {
            Dialog<ConnectionParams> dialog = new Dialog<>();
            dialog.setTitle("SSH 连接管理");
            dialog.setHeaderText("管理连接配置");

            ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(10));

            TextField hostField = new TextField();
            hostField.setPromptText("主机地址");
            TextField portField = new TextField();
            portField.setPromptText("端口");
            TextField usernameField = new TextField();
            usernameField.setPromptText("用户名");
            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("密码");
            TextField keyPathField = new TextField();
            keyPathField.setPromptText("私钥路径 (可选)");

            grid.add(new Label("主机:"), 0, 0);
            grid.add(hostField, 1, 0);
            grid.add(new Label("端口:"), 0, 1);
            grid.add(portField, 1, 1);
            grid.add(new Label("用户名:"), 0, 2);
            grid.add(usernameField, 1, 2);
            grid.add(new Label("密码:"), 0, 3);
            grid.add(passwordField, 1, 3);
            grid.add(new Label("私钥路径:"), 0, 4);
            grid.add(keyPathField, 1, 4);

            dialog.getDialogPane().setContent(grid);

            dialog.setResultConverter(dialogButton -> {
                if (dialogButton == saveButtonType) {
                    try {
                        String host = hostField.getText().isEmpty() ? "localhost" : hostField.getText();
                        int port = portField.getText().isEmpty() ? 22 : Integer.parseInt(portField.getText());
                        String username = usernameField.getText().isEmpty() ? "username" : usernameField.getText();
                        String password = passwordField.getText();
                        String privateKeyPath = keyPathField.getText().isEmpty() ? null : keyPathField.getText();

                        ConnectionParams params = new ConnectionParams();
                        params.setHost(host);
                        params.setPort(port);
                        params.setUsername(username);
                        params.setPassword(password);
                        params.setPrivateKeyPath(privateKeyPath);
                        return params;
                    } catch (NumberFormatException ex) {
                        log.error("端口号必须是数字", ex);
                        return null;
                    }
                }
                return null;
            });

            try {
                resultRef.set(dialog.showAndWait().orElse(null));
            } finally {
                latch.countDown();
            }
        };

        if (Platform.isFxApplicationThread()) {
            showDialog.run();
            return resultRef.get();
        }
        Platform.runLater(showDialog);

        try {
            if (latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                return resultRef.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public void refreshConnectionProfiles(javafx.scene.control.ComboBox<String> comboBox) {
        log.debug("Refreshing connection profiles");
        
        if (sshService != null && comboBox != null) {
            comboBox.getItems().clear();
            comboBox.getItems().add("新建连接...");

            Map<String, ConnectionParams> profiles = sshService.getAllConnectionProfiles();
            if (profiles != null) {
                for (String profileName : profiles.keySet()) {
                    if (profileName != null) {
                        comboBox.getItems().add(profileName);
                    }
                }
            }
        }
    }

    @Override
    public void initializeAIAdapter() {
        log.info("Initializing AI adapter");
        
        try {
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                this.aiAdapter = moduleManager.getAiService().getAIAdapter();
                log.info("AI adapter initialized successfully");
            }
        } catch (Exception e) {
            log.error("Failed to initialize AI adapter: {}", e.getMessage());
        }
    }

    @Override
    public void checkConnectionHealth(String tabId) {
        log.debug("Checking connection health for tab: {}", tabId);
        // sshService 已在构造函数中注入，不需要检查
        
        try {
            if (tabId == null) {
                log.warn("Tab ID is null, cannot check health");
                return;
            }
            
            com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
            if (connection != null) {
                boolean isConnected = connection.isConnected();
                log.debug("Tab {} connection status: {}", tabId, isConnected ? "CONNECTED" : "DISCONNECTED");

                if (!isConnected) {
                    log.warn("Tab {} is disconnected, attempting reconnection...", tabId);
                    attemptReconnectionByTabId(tabId);
                }
            } else {
                log.debug("No active connection found for tab: {}", tabId);
            }
        } catch (Exception e) {
            log.error("Error checking connection health for tab {}: {}", tabId, e.getMessage(), e);
        }
    }
    
    /**
     * 辅助方法：通过 Tab ID 尝试重连。
     *
     * <p>SSH 连接的自动重连由 {@link com.runzhi.aishell.ssh.SSHConnectionManager} 自身负责
     * （reader 线程检测到断线后触发 startReconnectProcess）。此健康检查为辅助入口，
     * 仅在连接层未自行恢复时由用户通过 {@link #attemptReconnection(javafx.scene.control.Tab)}
     * 手动重连，避免与连接层的重连逻辑并发执行产生重复会话。</p>
     */
    private void attemptReconnectionByTabId(String tabId) {
        log.warn("Tab {} connection lost; automatic reconnection is handled by the SSH connection layer. "
                + "If it does not recover, use the reconnect button.", tabId);
    }

    @Override
    public boolean isConnectionHealthy(String tabId) {
        // sshService 已在构造函数中注入，不需要检查
        if (tabId == null) {
            return false;
        }

        try {
            com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
            return connection != null && connection.isConnected();
        } catch (Exception e) {
            log.debug("Error checking connection health for tab {}: {}", tabId, e.getMessage());
            return false;
        }
    }
    
    @Override
    public void attemptReconnection(javafx.scene.control.Tab tab) {
        if (tab == null) {
            log.warn("Tab is null, cannot attempt reconnection");
            return;
        }
        
        String tabId = tab.getId();
        if (tabId == null) {
            log.warn("Tab ID is null, cannot attempt reconnection");
            return;
        }
        
        log.info("Attempting reconnection for tab: {}", tabId);
        
        // 实现完整的重连逻辑
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalTabService tabService = moduleManager.getTerminalTabService();
                if (tabService != null) {
                    InlineCssTextArea output = ((com.runzhi.aishell.service.impl.TerminalTabServiceImpl) tabService)
                        .getTabToOutput().get(tab);
                    if (output != null) {
                        reconnectTab(tabId, output);
                    } else {
                        log.warn("Output area not found for tab: {}", tabId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error during reconnection attempt for tab {}: {}", tabId, e.getMessage(), e);
        }
    }

    /** 从 Tab 内容树中查找已注册的 FileManagerPanelController */
    private static com.runzhi.aishell.controller.FileManagerPanelController findFileManagerController(
            javafx.scene.Node node) {
        if (node == null) return null;
        Object data = node.getUserData();
        if (data instanceof com.runzhi.aishell.controller.FileManagerPanelController fm) return fm;
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                var hit = findFileManagerController(child);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
