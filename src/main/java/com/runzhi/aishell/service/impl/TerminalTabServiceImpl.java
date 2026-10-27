/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service.impl;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.util.UITools;
import com.runzhi.aishell.controller.MonitorPanelController;
import com.runzhi.aishell.service.TerminalConnectionService;
import com.runzhi.aishell.service.TerminalUIService;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.service.TerminalCommandService;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;


import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 终端标签页服务实现
 * 负责终端 Tab 的创建、初始化、关闭等生命周期管理
 */
@Slf4j
public class TerminalTabServiceImpl implements TerminalTabService {

    private static final String HINT_TAB_ID = "terminal-hint-tab";

    private MonitorPanelController monitorPanelController;

    // 这两个映射由 FX 线程（创建/关闭标签页）与后台 SSH 读取线程（写入输出、清理）并发访问，
    // 必须使用并发映射，否则 HashMap 扩容时可能丢失条目或触发 ConcurrentModificationException
    private final Map<javafx.scene.control.Tab, InlineCssTextArea> tabToOutput = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<javafx.scene.control.Tab, TextField> tabToInput = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 设置监控面板控制器
     */
    public void setMonitorPanelController(MonitorPanelController monitorPanelController) {
        this.monitorPanelController = monitorPanelController;
    }

    @Override
    public void createNewTerminalTab() {
        log.debug("Creating new terminal tab without SSH connection");
        // 实现创建简单本地终端标签页的逻辑
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                javafx.scene.control.TabPane terminalTabPane = 
                    (javafx.scene.control.TabPane) com.runzhi.aishell.common.util.ParameterManager.getInstance().get("terminalTabPane");
                
                if (terminalTabPane != null) {
                    // 创建新的 Tab
                    javafx.scene.control.Tab newTab = new javafx.scene.control.Tab("本地终端");
                    newTab.setId("local_" + System.currentTimeMillis());
                    
                    // 设置终端界面 - 使用现有的 getTerminalAndFileManagerView 方法
                    TerminalUIService uiService = moduleManager.getTerminalUIService();
                    if (uiService != null) {
                        javafx.scene.Parent view = uiService.getTerminalAndFileManagerView();
                        if (view != null) {
                            newTab.setContent(view);
                            terminalTabPane.getTabs().add(newTab);
                            terminalTabPane.getSelectionModel().select(newTab);
                            log.info("Successfully created local terminal tab: {}", newTab.getText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to create new terminal tab: {}", e.getMessage(), e);
        }
    }

    @Override
    public void createNewTerminalTabWithParams(ConnectionParams params) {
        log.debug("Creating new terminal tab with params: {}:{}", params.getHost(), params.getPort());
        // 实现带参数的 Tab 创建逻辑
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalConnectionService connectionService = moduleManager.getTerminalConnectionService();
                javafx.scene.control.TabPane terminalTabPane = 
                    (javafx.scene.control.TabPane) com.runzhi.aishell.common.util.ParameterManager.getInstance().get("terminalTabPane");
                TerminalUIService uiService = moduleManager.getTerminalUIService();
                
                if (connectionService != null && uiService != null && terminalTabPane != null) {
                    // 创建新的 Tab
                    javafx.scene.control.Tab newTab = new javafx.scene.control.Tab(
                        params.getHost() + ":" + params.getPort());
                    newTab.setId("ssh_" + System.currentTimeMillis());
                    
                    // 设置终端界面
                    javafx.scene.Parent view = uiService.getTerminalAndFileManagerView();
                    if (view != null) {
                        newTab.setContent(view);
                        terminalTabPane.getTabs().add(newTab);
                        terminalTabPane.getSelectionModel().select(newTab);
                        
                        // 建立 SSH 连接
                        connectionService.establishSSHConnectionForTab(newTab.getId(), params, newTab);
                        
                        log.info("Successfully created SSH terminal tab: {}", newTab.getText());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to create new terminal tab with params: {}", e.getMessage(), e);
        }
    }

    @Override
    public void createNewTerminalTabWithParamsFromManager() {
        log.debug("Creating new terminal tab with params from manager");
        // 从参数管理器获取参数并创建 Tab
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager =
                    com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                // 从参数管理器获取连接参数
                Object paramsObj = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("last_connection_params");
                if (paramsObj instanceof ConnectionParams) {
                    ConnectionParams params = (ConnectionParams) paramsObj;
                    createNewTerminalTabWithParams(params);
                } else {
                    log.warn("No connection parameters found in parameter manager");
                    // 如果没有参数，创建本地终端
                    createNewTerminalTab();
                }
            }
        } catch (Exception e) {
            log.error("Failed to create new terminal tab from manager: {}", e.getMessage(), e);
        }
    }

    @Override
    public void setupTabTerminal(Tab tab, String tabId) {
        log.info("=== setupTabTerminal CALLED === Tab: {}, TabId: {}",
                tab != null ? tab.getText() : "null", tabId);

        if (tab == null) {
            log.warn("标签页为空，无法设置终端");
            return;
        }

        // 确保 SSH 服务已初始化
        SSHService sshService = getSSHService();
        if (sshService == null) {
            showError("SSH 服务未初始化，请检查模块管理器配置");
            return;
        }

        try {
            log.info("[SETUP_START] Starting setupTabTerminal for Tab: {}, TabId: {}", tab.getText(), tabId);

            // 获取 Tab 的当前内容
            Parent tabContent = (Parent) tab.getContent();
            log.info("[2] Got tab content: {}", tabContent != null ? tabContent.getClass().getSimpleName() : "null");
            InlineCssTextArea output = null;
            TextField input = null;
            Button executeButton = null;
            Button reconnectButton = null;
            ChoiceBox<String> modeComboBox = null;

            // 查找终端输出区域 - 支持多种容器类型
            if (tabContent != null) {
                // 先尝试直接查找（适用于 VBox 等直接容器）
                output = (InlineCssTextArea) findNodeById(tabContent, "terminalOutput");
                input = (TextField) findNodeById(tabContent, "commandInput");
                executeButton = (Button) findNodeById(tabContent, "executeButton");
                reconnectButton = (Button) findNodeById(tabContent, "reconnectButton");
                modeComboBox = (ChoiceBox<String>) findNodeById(tabContent, "inputModeChoiceBox");

                log.debug("从现有内容中查找控件 - 输出区域：{}, 输入框：{}, 执行按钮：{}, 重连按钮：{}, 模式选择框：{}",
                        output != null, input != null, executeButton != null, reconnectButton != null, modeComboBox != null);
            }

            // 如果没有找到输出区域，加载 FXML 文件
            if (output == null) {
                log.info("未找到现有输出区域，正在加载 FXML 文件");
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
                    loader.setResources(I18n.bundle());
                    VBox tabContentNew = loader.load();
                    UITools.setDarkTheme(tabContentNew);
                    com.runzhi.aishell.controller.TerminalTabController terminalTabController = loader.getController();

                    if (terminalTabController != null) {
                        terminalTabController.initializeDarkTheme();
                        output = terminalTabController.getTerminalOutput();
                        input = terminalTabController.getCommandInput();
                        executeButton = terminalTabController.getExecuteButton();
                        reconnectButton = terminalTabController.getReconnectButton();
                        modeComboBox = terminalTabController.getInputModeChoiceBox();

                        if (output == null) {
                            log.debug("Controls not available from controller, trying manual search");
                            output = (InlineCssTextArea) findNodeById(tabContentNew, "terminalOutput");
                            input = (TextField) findNodeById(tabContentNew, "commandInput");
                            executeButton = (Button) findNodeById(tabContentNew, "executeButton");
                            reconnectButton = (Button) findNodeById(tabContentNew, "reconnectButton");
                            modeComboBox = (ChoiceBox<String>) findNodeById(tabContentNew, "inputModeChoiceBox");
                        }
                    }

                    if (output == null) {
                        output = (InlineCssTextArea) findNodeById(tabContentNew, "terminalOutput");
                        input = (TextField) findNodeById(tabContentNew, "commandInput");
                        executeButton = (Button) findNodeById(tabContentNew, "executeButton");
                        reconnectButton = (Button) findNodeById(tabContentNew, "reconnectButton");
                        modeComboBox = (ChoiceBox<String>) findNodeById(tabContentNew, "inputModeChoiceBox");
                    }

                    tab.setContent(tabContentNew);
                } catch (Exception fxmlLoadException) {
                    log.error("加载 FXML 文件失败：{}", fxmlLoadException.getMessage(), fxmlLoadException);
                    output = new InlineCssTextArea();
                    input = new TextField();
                    executeButton = new Button("执行");
                    reconnectButton = new Button("重连");
                    modeComboBox = new ChoiceBox<>();
                    modeComboBox.getItems().addAll("普通模式", "AI 模式");
                    modeComboBox.setValue("普通模式");

                    VBox defaultContent = new VBox(output, input, executeButton, reconnectButton, modeComboBox);
                    tab.setContent(defaultContent);
                }
            }

            // 验证是否成功获取了必要的控件
            if (output == null) {
                log.error("❌ CANNOT GET OUTPUT AREA! Tab: {}, TabId: {}", tab.getText(), tabId);
                return;
            }

            log.info("✓ Output area is NOT null, proceeding with setup. Tab: {}, Output hash: {}", tab.getText(), output.hashCode());

            // 输出设置（支持颜色）
            // 输出区只负责显示和选择复制；命令统一从下方输入框进入，避免两套光标互相抢占。
            output.setEditable(false);
            output.setWrapText(true);
            output.setStyle(
                    "-fx-font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', monospace; " +
                            "-fx-font-size: 12px; " +
                            "-fx-text-fill: #e6edf3; " +
                            "-fx-control-inner-background: #0d1117; " +
                            "-fx-highlight-fill: #316dca; " +
                            "-fx-highlight-text-fill: #ffffff; " +
                            "-fx-prompt-text-fill: #888888; " +
                            "-fx-background: #0d1117; " +
                            "-fx-background-color: #0d1117; " +
                            "-fx-border-color: #30363d; " +
                            "-fx-border-width: 1px; " +
                            "-fx-opacity: 1.0; " +
                            "-fx-visible: true; " +
                            "-fx-pref-height: 400px; " +
                            "-fx-pref-width: 600px; " +
                            "-fx-min-height: 200px; " +
                            "-fx-min-width: 400px; " +
                            "-fx-background-radius: 6px; " +
                            "-fx-border-radius: 6px; " +
                            "-fx-padding: 10px; " +
                            "-fx-cursor: text; "
            );

            output.getStyleClass().add("terminal-output");

            // 设置输入框样式
            if (input != null) {
                input.getStyleClass().add("command-input");
                input.setStyle("-fx-font-family: 'Courier New', monospace; -fx-font-size: 12px; " +
                        "-fx-control-inner-background: #ffffff; -fx-text-fill: #000000;");
            }

            // 存储映射
            log.info("Storing output area for tab: {} (ID: {}), output area hash: {}", tab.getText(), tab.getId(), output.hashCode());
            tabToOutput.put(tab, output);
            if (input != null) {
                tabToInput.put(tab, input);
            }

            log.info("✓ Successfully stored mapping. Current tabToOutput size: {}", tabToOutput.size());
            
            // 初始化统一的命令输入、历史导航和补全。
            try {
                com.runzhi.aishell.common.ai.AIShellModuleManager mm = 
                    com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
                TerminalCommandService commandService = mm.getTerminalCommandService();
                if (commandService != null) {
                    commandService.initializeTabCommandHistory(tab);
                    if (input != null) {
                        commandService.setupCommandHistoryNavigation(input, tab);
                        commandService.setupTabShortcuts(tab, input);

                        TextField boundInput = input;
                        ChoiceBox<String> boundMode = modeComboBox;
                        Button boundExecute = executeButton;
                        Button boundReconnect = reconnectButton;
                        TerminalConnectionService connectionService = mm.getTerminalConnectionService();
                        if (boundMode == null) {
                            boundMode = new ChoiceBox<>();
                            boundMode.getItems().addAll("普通模式", "AI模式");
                            boundMode.setValue("普通模式");
                        }
                        ChoiceBox<String> finalMode = boundMode;
                        com.runzhi.aishell.terminal.TerminalInputBinder.bind(
                                boundInput, finalMode, boundExecute, boundReconnect,
                                () -> commandService.executeCurrentCommandInTab(tab, boundInput, finalMode),
                                connectionService == null ? null : () -> connectionService.attemptReconnection(tab));

                        TextField commandInput = input;
                        // 用户在输出区直接键入可打印字符时，将内容转交给命令输入框。
                        output.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, event -> {
                            if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                                    && event.getCharacter() != null
                                    && !event.getCharacter().isEmpty()
                                    && event.getCharacter().chars().allMatch(ch -> ch >= 0x20 && ch != 0x7f)) {
                                commandInput.appendText(event.getCharacter());
                                commandInput.requestFocus();
                                commandInput.positionCaret(commandInput.getLength());
                                event.consume();
                            }
                        });
                        Platform.runLater(() -> {
                            commandInput.requestFocus();
                            commandInput.positionCaret(commandInput.getLength());
                        });
                    }
                    log.info("✓ Command input initialized for tab: {}", tabId);
                }
            } catch (Exception cmdInputEx) {
                log.warn("Failed to setup output area command input: {}", cmdInputEx.getMessage());
            }

        } catch (Exception e) {
            log.error("❌ EXCEPTION in setupTabTerminal: {}", e.getMessage(), e);
            // 创建备用输出区域
            if (tab != null) {
                try {
                    InlineCssTextArea fallbackOutput = new InlineCssTextArea();
                    fallbackOutput.appendText("终端初始化失败：" + e.getMessage() + "\n");
                    fallbackOutput.setStyle(
                            "-fx-text-fill: #e6edf3; -fx-control-inner-background: #0d1117; "
                                    + "-fx-background-color: #0d1117; -fx-font-size: 12px;");
                    tabToOutput.put(tab, fallbackOutput);
                } catch (Exception fallbackException) {
                    log.error("创建回退输出区域失败：{}", fallbackException.getMessage());
                }
            }
        }
    }

    @Override
    public void initializeTerminalPanel(TabPane terminalTabPane) {
        if (terminalTabPane == null) {
            log.warn("Terminal tab pane is null");
            return;
        }
        log.info("Initializing terminal panel");
        // 实现初始化逻辑
        try {
            // 设置 TabPane 的样式
            terminalTabPane.getStyleClass().add("terminal-tab-pane");

            // 如果终端已由其他入口先创建，面板不应再补一个提示页。
            if (terminalTabPane.getTabs().stream()
                    .anyMatch(tab -> !HINT_TAB_ID.equals(tab.getId()))) {
                terminalTabPane.getTabs().removeIf(tab -> HINT_TAB_ID.equals(tab.getId()));
                log.debug("Terminal already has a real tab; skipping hint tab");
                return;
            }

            // 初始化可能被重复调用，只保留一个空状态提示页。
            if (terminalTabPane.getTabs().stream()
                    .anyMatch(tab -> HINT_TAB_ID.equals(tab.getId()))) {
                log.debug("Terminal hint tab already exists");
                return;
            }

            // 创建第一个提示标签页
            Tab hintTab = new Tab("提示");
            hintTab.setId(HINT_TAB_ID);
            hintTab.setClosable(false);

            // 创建提示内容
            InlineCssTextArea hintText = new InlineCssTextArea();
            hintText.setEditable(false);
            hintText.setWrapText(true);
            hintText.setTextInsertionStyle(
                    "欢迎使用 AI Shell!\n\n" +
                            "功能说明:\n" +
                            "1. 双击标签栏空白处可新建终端标签页\n" +
                            "2. Ctrl+T - 新建终端\n" +
                            "3. Ctrl+W - 关闭当前终端\n" +
                            "4. Ctrl+L - 清屏\n" +
                            "5. Ctrl+C - 中断命令/复制选中文本\n\n" +
                            "支持本地命令和 SSH 远程连接。\n"
            );

            hintTab.setContent(hintText);
            terminalTabPane.getTabs().add(hintTab);

            // 提示页仅代表“尚未打开终端”的空状态。任何入口创建真实终端后都移除它。
            terminalTabPane.getTabs().addListener(
                    (javafx.collections.ListChangeListener<Tab>) change -> {
                        boolean realTabAdded = false;
                        while (change.next()) {
                            if (change.wasAdded() && change.getAddedSubList().stream()
                                    .anyMatch(tab -> !HINT_TAB_ID.equals(tab.getId()))) {
                                realTabAdded = true;
                            }
                        }
                        if (realTabAdded && terminalTabPane.getTabs().contains(hintTab)) {
                            Platform.runLater(() -> terminalTabPane.getTabs().remove(hintTab));
                        }
                    });

            log.info("Terminal panel initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize terminal panel: {}", e.getMessage(), e);
        }
    }

    @Override
    public void setupTabCloseListener(TabPane terminalTabPane) {
        log.debug("Setting up tab close listener");
        // 添加 Tab 关闭监听器
        if (terminalTabPane != null) {
            // 为现有的每个 Tab 添加关闭事件处理器
            for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
                tab.setOnClosed(event -> {
                    // 执行关闭前的清理工作
                    cleanupTabResources(tab);
                });
            }

            // 监听新添加的 Tab 并为其添加关闭事件处理器
            terminalTabPane.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) {
                        for (javafx.scene.control.Tab addedTab : change.getAddedSubList()) {
                            addedTab.setOnClosed(event -> {
                                // 执行关闭前的清理工作
                                cleanupTabResources(addedTab);
                            });
                        }
                    }
                    // 关闭最后一个终端 tab 后返回连接列表，
                    // 否则用户面对空面板无处可去
                    if (change.wasRemoved() && terminalTabPane.getTabs().isEmpty()) {
                        com.runzhi.aishell.common.util.ParameterManager.getInstance()
                                .put("show_connection_list", true, 5000);
                    }
                }
            });
        }
    }

    @Override
    public void setupDoubleClickNewTab(TabPane terminalTabPane, Runnable createNewTerminalTabRunnable) {
        log.debug("Setting up double click new tab");
        // 支持双击新建终端 Tab
        if (terminalTabPane != null && createNewTerminalTabRunnable != null) {
            terminalTabPane.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    // 只有在当前没有终端标签页或只有提示标签页时才创建新终端
                    if (terminalTabPane.getTabs().isEmpty() ||
                            (terminalTabPane.getTabs().size() == 1 &&
                                    HINT_TAB_ID.equals(terminalTabPane.getTabs().get(0).getId()))) {
                        createNewTerminalTabRunnable.run();
                    }
                }
            });
        }
    }

    @Override
    public String getTabId(Tab tab) {
        if (tab == null) {
            return null;
        }
        return tab.getId() != null ? tab.getId() : tab.getText();
    }

    @Override
    public void clearTerminalOutput(Tab tab) {
        if (tab == null) {
            log.warn("Tab is null");
            return;
        }
        log.debug("Clearing terminal output for tab: {}", tab.getText());
        // 实现清空输出逻辑
        try {
            InlineCssTextArea output = tabToOutput.get(tab);
            if (output != null) {
                Platform.runLater(() -> {
                    output.clear();
                    output.appendText("终端输出已清空\n");
                    log.debug("Terminal output cleared for tab: {}", tab.getText());
                });
            } else {
                log.warn("Output area not found for tab: {}", tab.getText());
            }
        } catch (Exception e) {
            log.error("Failed to clear terminal output: {}", e.getMessage(), e);
        }
    }

    @Override
    public void copyTerminalOutput(Tab tab) {
        if (tab == null) {
            log.warn("Tab is null");
            return;
        }
        log.debug("Copying terminal output for tab: {}", tab.getText());
        // 实现复制输出逻辑
        try {
            InlineCssTextArea output = tabToOutput.get(tab);
            if (output != null) {
                Platform.runLater(() -> {
                    String selectedText = output.getSelectedText();
                    javafx.scene.input.ClipboardContent content = null;
                    if (selectedText != null && !selectedText.isEmpty()) {
                        // 复制选中的文本
                        content = new javafx.scene.input.ClipboardContent();
                        content.putString(selectedText);
                        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                        log.debug("Copied selected text from tab: {}", tab.getText());
                    } else {
                        // 复制全部文本
                        content.putString(output.getText());
                        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                        log.debug("Copied all text from tab: {}", tab.getText());
                    }
                });
            } else {
                log.warn("Output area not found for tab: {}", tab.getText());
            }
        } catch (Exception e) {
            log.error("Failed to copy terminal output: {}", e.getMessage(), e);
        }
    }

    @Override
    public void saveTerminalOutputToFile(Tab tab) {
        if (tab == null) {
            log.warn("Tab is null");
            return;
        }
        log.info("Saving terminal output to file for tab: {}", tab.getText());
        // 实现保存输出到文件逻辑
        try {
            InlineCssTextArea output = tabToOutput.get(tab);
            if (output != null) {
                Platform.runLater(() -> {
                    javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
                    fileChooser.setTitle("保存终端输出");
                    fileChooser.getExtensionFilters().add(
                            new javafx.stage.FileChooser.ExtensionFilter("文本文件", "*.txt"));
                    fileChooser.setInitialFileName(
                            "terminal_output_" +
                                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                                            .format(java.time.LocalDateTime.now()) + ".txt");

                    javafx.stage.Window window = output.getScene().getWindow();
                    if (window != null) {
                        java.io.File file = fileChooser.showSaveDialog(window);
                        if (file != null) {
                            try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                                writer.write(output.getText());
                                log.info("Successfully saved terminal output to: {}", file.getAbsolutePath());
                            } catch (java.io.IOException ioException) {
                                log.error("Failed to save terminal output: {}", ioException.getMessage(), ioException);
                                showError("保存失败：" + ioException.getMessage());
                            }
                        }
                    }
                });
            } else {
                log.warn("Output area not found for tab: {}", tab.getText());
            }
        } catch (Exception e) {
            log.error("Failed to save terminal output to file: {}", e.getMessage(), e);
        }
    }

    /**
     * 显示错误提示
     */
    private void showError(String message) {
        Platform.runLater(() -> {
            log.error("终端错误：{}", message);
            // 在 UI 上显示错误提示
            try {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("AI Shell - 错误");
                alert.setHeaderText("操作失败");
                alert.setContentText(message);
                // 不设置 Owner，使用默认行为
                alert.showAndWait();
            } catch (Exception e) {
                log.error("显示错误对话框失败：{}", e.getMessage());
            }
        });
    }

    /**
     * 清理 Tab 资源
     */
    private void cleanupTabResources(javafx.scene.control.Tab tab) {
        if (tab == null) return;

        String tabId = getTabId(tab);
        log.info("Cleaning up resources for tab: {} (ID: {})", tab.getText(), tabId);

        // 先停止终端模拟器的读取线程和 Shell channel。
        com.runzhi.aishell.terminal.InteractiveTerminalView.close(tab);
        disposeFileManager(tab.getContent());

        if (monitorPanelController != null) {
            monitorPanelController.removeSystemMonitorForTab(tabId);
        }

        // 摘除输出区上的全部增强模块（键盘过滤器、按 tab 的历史/状态缓存），再移除映射
        InlineCssTextArea closingOutput = tabToOutput != null ? tabToOutput.get(tab) : null;
        if (closingOutput != null) {
            try {
                com.runzhi.aishell.terminal.enhancement.TerminalEnhancementManager.getInstance()
                        .disableAllEnhancements(closingOutput, tabId);
            } catch (Exception e) {
                log.debug("禁用终端增强模块时出错: {}", e.getMessage());
            }
        }

        // 从映射中移除
        if (tabToOutput != null) {
            tabToOutput.remove(tab);
        }
        if (tabToInput != null) {
            tabToInput.remove(tab);
        }

        // 清理 SSH 连接
        SSHService sshService = getSSHService();
        if (sshService != null) {
            sshService.removeTabConnection(tabId);
            log.debug("Removed SSH connection for tab: {}", tabId);
        }

        // 清理命令历史
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager =
                    com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalCommandService commandService = moduleManager.getTerminalCommandService();
                if (commandService != null) {
                    log.debug("Cleaned up command history for tab: {}", tabId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup command history: {}", e.getMessage());
        }
    }

    private void disposeFileManager(javafx.scene.Node node) {
        if (node == null) return;
        if (node.getUserData() instanceof com.runzhi.aishell.controller.FileManagerPanelController controller) {
            controller.dispose();
        }
        if (node instanceof Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) disposeFileManager(child);
        }
        if (node instanceof javafx.scene.control.SplitPane splitPane) {
            for (javafx.scene.Node child : splitPane.getItems()) disposeFileManager(child);
        }
    }

    /**
     * 辅助方法：递归查找节点（支持 SplitPane 等特殊容器）
     */
    private Node findNodeById(Parent parent, String id) {
        // 先尝试使用 lookup 方法
        Node found = parent.lookup("#" + id);
        if (found != null) {
            return found;
        }

        // 处理 SplitPane 特殊情况
        if (parent instanceof javafx.scene.control.SplitPane) {
            javafx.scene.control.SplitPane splitPane = (javafx.scene.control.SplitPane) parent;
            for (Node item : splitPane.getItems()) {
                if (item.getId() != null && item.getId().equals(id)) {
                    return item;
                }
                if (item instanceof Parent) {
                    found = findNodeById((Parent) item, id);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        // 递归遍历子节点
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child.getId() != null && child.getId().equals(id)) {
                return child;
            } else if (child instanceof Parent) {
                found = findNodeById((Parent) child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 获取 Tab 到输出区域的映射
     */
    public Map<javafx.scene.control.Tab, InlineCssTextArea> getTabToOutput() {
        return tabToOutput;
    }

    /**
     * 获取 Tab 到输入框的映射
     */
    public Map<javafx.scene.control.Tab, TextField> getTabToInput() {
        return tabToInput;
    }
    
    /**
     * 获取 SSH 服务 - 从模块管理器懒加载获取
     */
    private SSHService getSSHService() {
        try {
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                return moduleManager.getSshService();
            }
        } catch (Exception e) {
            log.error("Failed to get SSH service from module manager: {}", e.getMessage(), e);
        }
        return null;
    }
}