/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service.impl;

import com.runzhi.aishell.common.util.TerminalUtils;
import com.runzhi.aishell.controller.FileManagerPanelController;
import com.runzhi.aishell.controller.MonitorPanelController;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.service.TerminalUIService;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;


import java.io.IOException;

/**
 * 终端 UI 服务实现
 * 负责终端的 UI 交互、样式、快捷键等
 */
@Slf4j
public class TerminalUIServiceImpl implements TerminalUIService {

    /** SFTP 抽屉默认展开高度（文档 §12：展开默认 300px） */
    private static final double DEFAULT_DRAWER_HEIGHT = 300;
    /** 最小高度（文档 §12：最小 180px） */
    private static final double MIN_DRAWER_HEIGHT = 180;
    /** 最大占窗口高度比例（文档 §12：最大 60%） */
    private static final double MAX_DRAWER_RATIO = 0.6;

    private MonitorPanelController monitorPanelController;
    private FileManagerPanelController fileManagerPanelController;

    /** 记忆每个 Tab 的抽屉展开高度 */
    private static final java.util.prefs.Preferences DRAWER_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(TerminalUIServiceImpl.class);

    private double loadDrawerHeight(String tabId) {
        return DRAWER_PREFS.getDouble("drawer.height." + tabId, DEFAULT_DRAWER_HEIGHT);
    }

    private void saveDrawerHeight(String tabId, double height) {
        DRAWER_PREFS.putDouble("drawer.height." + tabId, height);
    }
    
    // Deprecated - 此方法已废弃，SSH 服务将从模块管理器懒加载获取
    @SuppressWarnings("deprecated")
    public void setDependencies(SSHService sshService) {
        log.debug("setDependencies deprecated - SSH service not needed in this service");
    }
    
    @Override
    public void setupTabShortcuts(Tab tab, TextField input) {
        log.debug("Setting up tab shortcuts for tab: {}", tab != null ? tab.getText() : "null");
        
        if (tab == null || input == null) {
            log.warn("Tab or input is null");
            return;
        }
        
        // Tab 键补全
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.TAB) {
                String currentText = input.getText();
                if (currentText != null && !currentText.isEmpty()) {
                    // 这里可以调用命令补全逻辑
                    log.debug("Tab key pressed for auto-completion: {}", currentText);
                }
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.L) {
                // Ctrl+L 清屏
                clearScreen(tab);
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.K) {
                // Ctrl+K 清空输出
                clearTerminalOutput(tab);
                event.consume();
            }
        });
    }

    @Override
    public void setupKeyboardShortcuts(TabPane terminalTabPane) {
        log.debug("Setting up keyboard shortcuts for TabPane");
        
        if (terminalTabPane == null) {
            log.warn("Terminal tab pane is null");
            return;
        }
        
        // TabPane 级别的快捷键
        terminalTabPane.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.T) {
                // Ctrl+T 创建新标签页（需要外部处理）
                log.debug("Ctrl+T pressed - should create new tab");
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.W) {
                // Ctrl+W 关闭当前标签页
                Tab selectedTab = terminalTabPane.getSelectionModel().getSelectedItem();
                if (selectedTab != null) {
                    terminalTabPane.getTabs().remove(selectedTab);
                    log.debug("Ctrl+W pressed - closed tab: {}", selectedTab.getText());
                }
                event.consume();
            }
        });
    }

    @Override
    public void applyAnsiCodesToText(InlineCssTextArea textArea, String text) {
        if (textArea == null || text == null) {
            return;
        }
        
        try {
            // 纯文本落点：cleanControlSequences 保留 SGR，此处追加前显式去颜色码（2026-10-23 审计 P1-12）
            String cleanedText = TerminalUtils.cleanControlSequences(text);
            cleanedText = TerminalUtils.ANSI_COLOR_PATTERN.matcher(cleanedText).replaceAll("");
            textArea.appendText(cleanedText);
        } catch (Exception e) {
            log.error("Error applying ANSI codes: {}", e.getMessage());
            textArea.appendText(text);
        }
    }

    @Override
    public String getStyleForAnsiCode(String ansiCode, String defaultStyle) {
        if (ansiCode == null || ansiCode.isEmpty()) {
            return defaultStyle;
        }
        
        // 实现 ANSI 代码到 CSS 样式的映射
        try {
            // 解析 ANSI 代码 (格式：ESC[<code>m)
            String code = ansiCode.replaceAll("\\u001B\\[|m", "");
            
            // 颜色映射
            switch (code) {
                case "0": // 重置
                    return "-fx-text-fill: #ffffff;";
                case "1": // 加粗
                    return "-fx-font-weight: bold;";
                case "30": // 黑色
                    return "-fx-text-fill: #000000;";
                case "31": // 红色
                    return "-fx-text-fill: #ff5555;";
                case "32": // 绿色
                    return "-fx-text-fill: #50fa7b;";
                case "33": // 黄色
                    return "-fx-text-fill: #f1fa8c;";
                case "34": // 蓝色
                    return "-fx-text-fill: #bd93f9;";
                case "35": // 紫色
                    return "-fx-text-fill: #ff79c6;";
                case "36": // 青色
                    return "-fx-text-fill: #8be9fd;";
                case "37": // 白色
                    return "-fx-text-fill: #bbbbbb;";
                case "40": // 黑色背景
                    return "-fx-background-color: #000000;";
                case "41": // 红色背景
                    return "-fx-background-color: #ff5555;";
                case "42": // 绿色背景
                    return "-fx-background-color: #50fa7b;";
                case "43": // 黄色背景
                    return "-fx-background-color: #f1fa8c;";
                case "44": // 蓝色背景
                    return "-fx-background-color: #bd93f9;";
                case "45": // 紫色背景
                    return "-fx-background-color: #ff79c6;";
                case "46": // 青色背景
                    return "-fx-background-color: #8be9fd;";
                case "47": // 白色背景
                    return "-fx-background-color: #bbbbbb;";
                default:
                    return defaultStyle;
            }
        } catch (Exception e) {
            log.warn("解析 ANSI 代码失败：{}", e.getMessage());
            return defaultStyle;
        }
    }

    @Override
    public void integrateFileManager(Tab tab) {
        log.info("Integrating file manager for tab: {}", tab != null ? tab.getText() : "null");
        
        if (tab == null) {
            log.warn("Tab is null");
            return;
        }
        
        // 文件管理器集成逻辑
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                // 刷新文件列表
                refreshFileList();
            }
        } catch (Exception e) {
            log.error("Failed to integrate file manager: {}", e.getMessage());
        }
    }

    @Override
    public Parent createTerminalAndFileManagerViewForTab(String tabId) {
        log.debug("Creating terminal and file manager view for tab: {}", tabId);
        
        if (tabId == null) {
            log.warn("Tab ID is null");
            return null;
        }
        
        try {
            // 【SFTP Drawer 化】文件管理器不再是永久分栏，而是底部可折叠抽屉：
            // 默认收起，终端获得完整高度；点击抽屉头展开，高度可拖动并记忆。
            VBox container = new VBox();
            container.getStyleClass().add("terminal-drawer-container");

            // 创建终端 Tab 的界面
            FXMLLoader terminalTabLoader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
            terminalTabLoader.setResources(I18n.bundle());
            VBox terminalTabContent = terminalTabLoader.load();
            com.runzhi.aishell.controller.TerminalTabController terminalTabController = terminalTabLoader.getController();
            if (terminalTabController != null) {
                container.getProperties().put("terminalTabController", terminalTabController);
            }
            log.debug("Loaded terminal-tab.fxml for tab: {}", tabId);

            VBox terminalBox = new VBox();
            terminalBox.getChildren().add(terminalTabContent);
            VBox.setVgrow(terminalTabContent, javafx.scene.layout.Priority.ALWAYS);
            VBox.setVgrow(terminalBox, javafx.scene.layout.Priority.ALWAYS);
            log.info("Created terminal box for tab: {}", tabId);

            // 文件管理器部分 - 动态加载
            FXMLLoader fileManagerLoader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"));
            fileManagerLoader.setResources(I18n.bundle());
            Parent fileManagerRoot = fileManagerLoader.load();
            log.debug("Loaded file-manager-panel.fxml for tab: {}", tabId);

            // 获取文件管理器控制器并设置 SSH 服务
            FileManagerPanelController fileManagerController = fileManagerLoader.getController();
            log.info("FileManagerPanelController instance: {}", fileManagerController);
            if (fileManagerController != null) {
                // 从模块管理器获取 SSH 服务
                try {
                    com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager =
                        com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
                    if (moduleManager != null && moduleManager.getSshService() != null) {
                        fileManagerController.setSshService(moduleManager.getSshService(), tabId);
                        fileManagerRoot.setUserData(fileManagerController);
                        fileManagerController.setPanelVisible(false);
                        log.info("已为 Tab {} 设置文件管理器控制器的 SSH 服务", tabId);
                    } else {
                        log.warn("ModuleManager or SSHService is null for tab: {}", tabId);
                    }
                } catch (Exception e) {
                    log.error("Failed to get SSH service from module manager: {}", e.getMessage());
                }
            } else {
                log.error("FileManagerPanelController is null for tab: {}", tabId);
            }

            // 文件管理器包裹层：默认收起，展开时占据固定高度（可拖动调整）
            VBox fileManagerBox = new VBox();
            fileManagerBox.getChildren().add(fileManagerRoot);
            VBox.setVgrow(fileManagerRoot, javafx.scene.layout.Priority.ALWAYS);
            fileManagerBox.getStyleClass().add("file-drawer-body");
            // 默认收起：不占布局也不显示
            fileManagerBox.setManaged(false);
            fileManagerBox.setVisible(false);
            if (fileManagerController != null) {
                fileManagerController.setPanelVisible(false);
            }
            if (fileManagerController != null) {
                fileManagerController.setPanelVisible(false);
            }
            // 展开高度记忆（每 Tab 独立）
            javafx.beans.property.SimpleDoubleProperty drawerHeight =
                    new javafx.beans.property.SimpleDoubleProperty(loadDrawerHeight(tabId));
            fileManagerBox.minHeightProperty().bind(drawerHeight);
            fileManagerBox.maxHeightProperty().bind(drawerHeight);
            log.info("Created file manager drawer box for tab: {}", tabId);

            // 抽屉头：点击切换展开/收起
            javafx.scene.control.Label drawerHeader = new javafx.scene.control.Label("📁 文件 ▲");
            drawerHeader.getStyleClass().add("file-drawer-header");
            drawerHeader.setMaxWidth(Double.MAX_VALUE);
            drawerHeader.setCursor(javafx.scene.Cursor.HAND);
            // 用箭头方向反映当前状态
            drawerHeader.textProperty().bind(
                javafx.beans.binding.Bindings.createStringBinding(
                    () -> fileManagerBox.isManaged() ? "📁 文件 ▼" : "📁 文件 ▲",
                    fileManagerBox.managedProperty()));
            drawerHeader.setOnMouseClicked(e -> {
                boolean open = !fileManagerBox.isManaged();
                fileManagerBox.setManaged(open);
                fileManagerBox.setVisible(open);
                if (fileManagerController != null) {
                    fileManagerController.setPanelVisible(open);
                }
                saveDrawerHeight(tabId, drawerHeight.get());
            });
            // 双击抽屉头重置为默认高度
            drawerHeader.setOnZoomFinished(e -> drawerHeight.set(DEFAULT_DRAWER_HEIGHT));

            // 拖动抽屉头边缘调整高度（拖住抽屉头上下移动）
            final double[] dragStart = new double[1];
            final double[] dragStartHeight = new double[1];
            drawerHeader.setOnMousePressed(e -> {
                dragStart[0] = e.getScreenY();
                dragStartHeight[0] = drawerHeight.get();
            });
            drawerHeader.setOnMouseDragged(e -> {
                double delta = dragStart[0] - e.getScreenY(); // 向上拖增大
                double h = Math.min(MAX_DRAWER_RATIO * container.getHeight(),
                        Math.max(MIN_DRAWER_HEIGHT, dragStartHeight[0] + delta));
                drawerHeight.set(h);
            });
            drawerHeader.setOnMouseReleased(e -> saveDrawerHeight(tabId, drawerHeight.get()));

            container.getChildren().addAll(terminalBox, drawerHeader, fileManagerBox);

            log.info("Successfully created drawer view for tab: {}", tabId);
            return container;
        } catch (Exception e) {
            log.error("创建终端和文件管理器组合界面失败", e);
            // 如果加载失败，返回原始的终端界面
            try {
                FXMLLoader terminalTabLoader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
                terminalTabLoader.setResources(I18n.bundle());
            terminalTabLoader.setResources(I18n.bundle());
                VBox terminalTabContent = terminalTabLoader.load();
                com.runzhi.aishell.controller.TerminalTabController terminalTabController = terminalTabLoader.getController();
                if (terminalTabController != null) {
                    terminalTabContent.getProperties().put("terminalTabController", terminalTabController);
                }

                VBox terminalOnlyBox = new VBox();
                terminalOnlyBox.getChildren().add(terminalTabContent);
                VBox.setVgrow(terminalTabContent, javafx.scene.layout.Priority.ALWAYS);
                return terminalOnlyBox;
            } catch (IOException ex) {
                log.error("创建终端界面备用方案失败", ex);
                return new VBox(); // 返回空容器
            }
        }
    }

    @Override
    public Parent getTerminalAndFileManagerView() {
        log.info("Getting terminal and file manager view");
        
        try {
            // 创建一个 SplitPane 来同时显示终端和文件管理器
            javafx.scene.control.SplitPane splitPane = new javafx.scene.control.SplitPane();
            splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
    
            // 创建终端 Tab 的界面
            FXMLLoader terminalTabLoader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
            terminalTabLoader.setResources(I18n.bundle());
            VBox terminalTabContent = terminalTabLoader.load();
    
            VBox terminalBox = new VBox();
            terminalBox.getChildren().add(terminalTabContent);
            VBox.setVgrow(terminalTabContent, javafx.scene.layout.Priority.ALWAYS);
    
            // 文件管理器部分 - 动态加载
            FXMLLoader fileManagerLoader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"));
            fileManagerLoader.setResources(I18n.bundle());
            Parent fileManagerRoot = fileManagerLoader.load();
                
            // 获取文件管理器控制器并设置 SSH 服务 (如果有默认连接)
            FileManagerPanelController fileManagerController = fileManagerLoader.getController();
            if (fileManagerController != null) {
                // 从模块管理器获取 SSH 服务
                try {
                    com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                        com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
                    if (moduleManager != null && moduleManager.getSshService() != null) {
                        fileManagerController.setSshService(moduleManager.getSshService(), "default");
                        // 将控制器设置为 userData，方便后续查找
                        fileManagerRoot.setUserData(fileManagerController);
                        log.info("已为默认视图设置文件管理器控制器的 SSH 服务");
                    }
                } catch (Exception e) {
                    log.error("Failed to get SSH service from module manager: {}", e.getMessage());
                }
            }
    
            VBox fileManagerBox = new VBox();
            fileManagerBox.getChildren().add(fileManagerRoot);
            VBox.setVgrow(fileManagerRoot, javafx.scene.layout.Priority.ALWAYS);
    
            // 添加到 SplitPane
            splitPane.getItems().addAll(terminalBox, fileManagerBox);
    
            // 设置分割位置
            splitPane.setDividerPositions(0.6);
    
            return splitPane;
        } catch (Exception e) {
            log.error("创建终端和文件管理器组合界面失败", e);
            // 如果加载失败，返回原始的终端界面
            try {
                FXMLLoader terminalTabLoader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
                terminalTabLoader.setResources(I18n.bundle());
            terminalTabLoader.setResources(I18n.bundle());
                VBox terminalTabContent = terminalTabLoader.load();
    
                VBox terminalOnlyBox = new VBox();
                terminalOnlyBox.getChildren().add(terminalTabContent);
                VBox.setVgrow(terminalTabContent, javafx.scene.layout.Priority.ALWAYS);
                return terminalOnlyBox;
            } catch (IOException ex) {
                log.error("创建终端界面备用方案失败", ex);
                return new VBox(); // 返回空容器
            }
        }
    }

    @Override
    public void testAnsiColors(InlineCssTextArea textArea) {
        log.debug("Testing ANSI colors");
        
        if (textArea == null) {
            log.warn("Text area is null");
            return;
        }
        
        // 使用 TerminalOutputHandler 处理 ANSI 颜色
        com.runzhi.aishell.terminal.handler.TerminalOutputHandler.AnsiColorProcessor.processAnsiTextFull(
            textArea, 
            "\u001B[31m 红色文本\u001B[0m 和 \u001B[32m 绿色文本\u001B[0m\n"
        );
    }

    @Override
    public void clearScreen(Tab tab) {
        if (tab == null) return;
        
        String tabId = getTabId(tab);
        
        // 从模块管理器获取 SSH 服务
        SSHService sshService = null;
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                sshService = moduleManager.getSshService();
            }
        } catch (Exception e) {
            log.error("Failed to get SSH service from module manager: {}", e.getMessage());
        }
        
        if (tabId != null && sshService != null) {
            com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
            if (connection != null && connection.isConnected()) {
                // 发送 clear 命令到远程服务器
                connection.sendCommand("clear\n");
            }
        }

        // 同时清空本地输出区域
        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            Platform.runLater(() -> {
                outputArea.clear();
                outputArea.appendText("屏幕已清空\n");
            });
        }
    }

    @Override
    public void clearTerminalOutput(Tab tab) {
        if (tab == null) return;
        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            Platform.runLater(() -> {
                outputArea.clear();
                outputArea.appendText("终端输出已清空\n");
            });
        }
    }

    @Override
    public void copyTerminalOutput(Tab tab) {
        if (tab == null) return;
        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            Platform.runLater(() -> {
                outputArea.copy();
            });
        }
    }

    @Override
    public void saveTerminalOutputToFile(Tab tab) {
        if (tab == null) return;
        // 实现保存终端输出到文件的功能
        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            Platform.runLater(() -> {
                // 创建文件保存对话框
                javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
                fileChooser.setTitle("保存终端输出");
                fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("文本文件", "*.txt"));
                fileChooser.setInitialFileName("terminal_output_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now()) + ".txt");

                javafx.stage.Window window = outputArea.getScene().getWindow();
                if (window != null) {
                    java.io.File file = fileChooser.showSaveDialog(window);

                    if (file != null) {
                        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                            writer.write(outputArea.getText());
                        } catch (java.io.IOException e) {
                            log.error("保存终端输出到文件失败：{}", e.getMessage(), e);
                        }
                    }
                }
            });
        }
    }

    @Override
    public void pasteToTerminal(Tab tab) {
        if (tab == null) return;
        // 实现粘贴到终端的功能
        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            Platform.runLater(() -> {
                // 从剪贴板获取文本并附加到输出区域
                javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                if (clipboard.hasString()) {
                    String clipboardContent = clipboard.getString();
                    if (clipboardContent != null) {
                        outputArea.appendText(clipboardContent);
                    }
                }
            });
        }
    }

    @Override
    public void setMonitorPanelController(MonitorPanelController monitorPanelController) {
        this.monitorPanelController = monitorPanelController;
        log.info("Monitor panel controller set");
    }

    @Override
    public void refreshFileList() {
        log.debug("Refreshing file list");
        
        // 直接调用已设置的 FileManagerPanelController
        if (fileManagerPanelController != null) {
            javafx.application.Platform.runLater(() -> {
                fileManagerPanelController.refreshFileList();
                log.debug("File list refreshed via file manager controller");
            });
        } else {
            log.debug("FileManagerPanelController not set, skipping file list refresh");
        }
    }

    @Override
    public void setFileManagerPanelController(FileManagerPanelController fileManagerPanelController) {
        this.fileManagerPanelController = fileManagerPanelController;
        log.info("File manager panel controller set");
    }
    
    @Override
    public void setupInputModeChoiceBox(ChoiceBox<String> inputModeChoiceBox, TextField commandInput) {
        log.debug("Setting up input mode choice box");
        if (inputModeChoiceBox != null && commandInput != null) {
            // 监听模式选择变化
            inputModeChoiceBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                log.debug("Input mode changed to: {}", newVal);
                // 可以在这里添加模式切换的逻辑
            });
        }
    }
    
    @Override
    public void setupTabContextMenu(TabPane terminalTabPane) {
        log.debug("Setting up tab context menu");
        // 添加标签页右键菜单
        if (terminalTabPane != null) {
            // 为 TabPane 中的每个 Tab 添加右键菜单
            for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
                setupSingleTabContextMenu(tab, terminalTabPane);
            }

            // 监听新添加的 Tab 并为其添加右键菜单
            terminalTabPane.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) {
                        for (javafx.scene.control.Tab addedTab : change.getAddedSubList()) {
                            setupSingleTabContextMenu(addedTab, terminalTabPane);
                        }
                    }
                }
            });
        }
    }
    
    /**
     * 为单个 Tab 设置右键菜单
     */
    private void setupSingleTabContextMenu(javafx.scene.control.Tab tab, TabPane tabPane) {
        if (tab == null || tabPane == null) return;

        javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();

        javafx.scene.control.MenuItem closeMenuItem = new javafx.scene.control.MenuItem("关闭");
        closeMenuItem.setOnAction(event -> {
            tabPane.getTabs().remove(tab);
        });

        javafx.scene.control.MenuItem closeOthersMenuItem = new javafx.scene.control.MenuItem("关闭其他");
        closeOthersMenuItem.setOnAction(event -> {
            java.util.List<javafx.scene.control.Tab> tabsToRemove = new java.util.ArrayList<>();
            for (javafx.scene.control.Tab otherTab : tabPane.getTabs()) {
                if (otherTab != null && !otherTab.equals(tab)) {
                    tabsToRemove.add(otherTab);
                }
            }
            tabPane.getTabs().removeAll(tabsToRemove);
        });

        javafx.scene.control.MenuItem closeAllMenuItem = new javafx.scene.control.MenuItem("关闭全部");
        closeAllMenuItem.setOnAction(event -> {
            tabPane.getTabs().clear();
        });

        contextMenu.getItems().addAll(closeMenuItem, closeOthersMenuItem, closeAllMenuItem);
        tab.setContextMenu(contextMenu);
    }
    
    /**
     * 获取 Tab ID（辅助方法）
     */
    private String getTabId(Tab tab) {
        if (tab == null) return null;
        
        // 尝试从 Tab 的属性中获取 ID
        if (tab.getId() != null) {
            return tab.getId();
        }
        
        // 尝试从 Tab 的 userData 中获取 ID
        if (tab.getUserData() != null) {
            return String.valueOf(tab.getUserData());
        }
        
        // 如果都没有，使用 Tab 的文本作为 ID
        return tab.getText() != null ? tab.getText() : String.valueOf(System.identityHashCode(tab));
    }
    
    /**
     * 获取 Tab 对应的输出区域（辅助方法）
     */
    private InlineCssTextArea getOutputAreaForTab(Tab tab) {
        if (tab == null) return null;
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalTabService tabService = moduleManager.getTerminalTabService();
                if (tabService instanceof TerminalTabServiceImpl) {
                    return ((TerminalTabServiceImpl) tabService).getTabToOutput().get(tab);
                }
            }
        } catch (Exception e) {
            log.warn("无法获取 Tab 的输出区域：{}", e.getMessage());
        }
        return null;
    }

    // ==================== 文件管理器控制器提取 ====================

    /**
     * 从视图中提取文件管理器控制器
     *
     * @param view 视图根节点
     * @return 文件管理器控制器，如果未找到返回 null
     */
    public FileManagerPanelController extractFileManagerController(Parent view) {
        if (view == null) return null;

        try {
            // 如果是 SplitPane，查找包含文件管理器的部分
            if (view instanceof javafx.scene.control.SplitPane) {
                javafx.scene.control.SplitPane splitPane = (javafx.scene.control.SplitPane) view;
                for (javafx.scene.Node node : splitPane.getItems()) {
                    FileManagerPanelController controller = findFileManagerControllerInNode(node);
                    if (controller != null) {
                        return controller;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract file manager controller: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 递归在节点树中查找 FileManagerPanelController
     *
     * @param node 节点
     * @return 文件管理器控制器，如果未找到返回 null
     */
    private FileManagerPanelController findFileManagerControllerInNode(javafx.scene.Node node) {
        if (node == null) return null;

        // 通过 userData 获取控制器
        Object userData = node.getUserData();
        if (userData instanceof FileManagerPanelController) {
            return (FileManagerPanelController) userData;
        }

        // 如果是 Parent，递归查找子节点
        if (node instanceof Parent) {
            for (javafx.scene.Node child : ((Parent) node).getChildrenUnmodifiable()) {
                FileManagerPanelController found = findFileManagerControllerInNode(child);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }
}
