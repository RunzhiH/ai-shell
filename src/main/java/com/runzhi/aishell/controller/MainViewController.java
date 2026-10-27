/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIService;
import com.runzhi.aishell.ai.AIConfigManager;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.config.StartupPage;
import com.runzhi.aishell.common.enhancement.ShellEnhancementManager;
import com.runzhi.aishell.common.enhancement.ThemeEnhancer;
import com.runzhi.aishell.common.util.ButtonManager;
import com.runzhi.aishell.common.util.DialogUtils;
import com.runzhi.aishell.common.util.SystemInfoProvider;
import com.runzhi.aishell.common.util.UITools;
import com.runzhi.aishell.common.validation.InputValidator;
import com.runzhi.aishell.common.validation.BoundaryChecker;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class MainViewController extends BaseController implements Initializable {

    // 常量定义
    private static final String DEFAULT_STATUS_READY = "就绪";
    private static final String DEFAULT_STATUS_DISCONNECTED = "已断开";
    private static final String DEFAULT_STATUS_CONNECTING = "已连接到 ";
    private static final String ABOUT_DIALOG_TITLE = "关于 AIShell";
    private static final String ABOUT_DIALOG_HEADER = "AIShell - SSH & AI Assistant";
    private static final String ABOUT_DIALOG_CONTENT = "版本: " + AppConstants.APP_VERSION + "\n这是一个集成了SSH连接和AI助手功能的桌面应用。\n\n作者: Runzhi\n\n基于JavaFX开发";

    @FXML
    private Label statusLabel;

    // ===== V1 主布局：Server | Terminal（Agent 在终端内；无右侧 AI 面板） =====
    @FXML
    private javafx.scene.layout.VBox serverPanel;
    @FXML
    private javafx.scene.layout.VBox serverContent;
    @FXML
    private Label currentHostLabel;
    @FXML
    private Label hostStatusDot;
    @FXML
    private Label statusHostLabel;
    @FXML
    private Label statusHealthLabel;
    @FXML
    private Label statusAiLabel;
    @FXML
    private Label statusTerminalTab;
    @FXML
    private javafx.scene.control.Button btnTheme;
    @FXML
    private javafx.scene.control.Button btnToggleServer;
    @FXML
    private javafx.scene.control.Button btnCollapseServer;
    @FXML
    private javafx.scene.control.Button btnFilesDrawer;

    @FXML
    private javafx.scene.control.Button navConnections;
    @FXML
    private javafx.scene.control.Button navTerminal;
    @FXML
    private javafx.scene.control.Button navFiles;
    @FXML
    private javafx.scene.control.Button navMonitor;
    @FXML
    private javafx.scene.control.Button navAI;

    // 系统信息标签（兼容旧侧栏监控逻辑，字段可为空）
    @FXML
    private Label uptimeLabel;

    @FXML
    private Label loadLabel;

    @FXML
    private Label cpuLabel;

    @FXML
    private Label memoryLabel;

    @FXML
    private Label swapLabel;

    // 存储信息表格
    @FXML
    private TableView storageTable;

    @FXML
    private TableColumn pathColumn;

    @FXML
    private TableColumn sizeColumn;


    @FXML
    private javafx.scene.layout.StackPane contentArea;  // 主内容区域
    @FXML
    private javafx.scene.control.SplitPane mainSplitPane;

    // 布局与主题（V1 §35-36）
    private static final java.util.prefs.Preferences LAYOUT_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(MainViewController.class);
    private static final String KEY_SERVER_OPEN = "layout.server.open";
    private static final String KEY_SERVER_DIVIDER = "layout.server.divider";
    private static final String KEY_THEME = "layout.theme";
    private boolean serverOpen = true;

    // 各面板的控制器实例
    private MonitorPanelController monitorPanelController;
    private TerminalPanelController terminalPanelController;
    private ConnectionListController connectionListController;
    private AIAssistantPanelController aiAssistantPanelController;
    private WelcomePageController welcomePageController;  // 欢迎页面控制器

    // 模块管理器实例（替代原来的pageManager等）
    private AIShellModuleManager moduleManager;

    // 主要服务实例
    private SSHService sshService;
    private SystemMonitor systemMonitor;
    private final Map<String, SystemMonitor> sidebarMonitors = new java.util.concurrent.ConcurrentHashMap<>();
    private SystemMonitor activeSidebarMonitor;
    private String activeSidebarTabId;
    private AIAdapter aiAdapter;
    private AIService aiService;



    // 按钮管理器
    private ButtonManager buttonManager;

    // 视图切换器
    private com.runzhi.aishell.common.util.ViewSwitcher viewSwitcher;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 调用父类初始化方法，确保parameterManager等字段被正确初始化
        super.initialize(location, resources);

        if (statusLabel != null) {
            statusLabel.setText(DEFAULT_STATUS_READY);
        }

        // 初始化模块管理器（替代之前的pageManager）
        moduleManager = AIShellModuleManager.getInstance();
        sshService = moduleManager.getSshService();
        initializeSidebarStorageTable();

        // 初始化按钮管理器
        buttonManager = ButtonManager.getInstance();

        // 初始化视图切换器
        viewSwitcher = new com.runzhi.aishell.common.util.ViewSwitcher(contentArea);

        // V1：默认深色；无右侧 AI 面板；Agent 在终端内
        serverOpen = LAYOUT_PREFS.getBoolean(KEY_SERVER_OPEN, true);
        setupLayoutPersistence();
        applyPanelVisibility();
        loadServerListIntoPanel();
        setupV1Shortcuts();
        restoreTheme();
        refreshThemeLabel();
        // 监听连接参数 → 同步顶栏/状态栏目标主机
        com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters()
            .addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                if ("connection_params".equals(change.getKey()) && change.wasAdded()) {
                    Object value = change.getValueAdded();
                    if (value instanceof ConnectionParams params) {
                        Platform.runLater(() -> updateCurrentHostDisplay(params));
                    }
                }
            });

        // 检查是否是首次启动（没有保存的连接）
        boolean isFirstLaunch = checkFirstLaunch();

        if (isFirstLaunch) {
            // 首次启动显示欢迎页面
            Platform.runLater(this::showWelcomePage);
        } else {
            Platform.runLater(this::openConfiguredStartupPage);
        }

        // 监听参数管理器中的显示连接列表请求（直接监听底层 ObservableMap，绕过不可变包装）
        com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters()
            .addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                if ("show_connection_list".equals(change.getKey()) && change.wasAdded()) {
                    Boolean showList = (Boolean) change.getValueAdded();
                    if (showList != null && showList) {
                        Platform.runLater(this::initializeConnectionList);
                    }
                } else if ("switch_to_terminal".equals(change.getKey()) && change.wasAdded()) {
                    Boolean switchToTerminal = (Boolean) change.getValueAdded();
                    if (switchToTerminal != null && switchToTerminal) {
                        Platform.runLater(this::switchToTerminalViewWithParams);
                    }
                } else if ("switch_to_welcome".equals(change.getKey()) && change.wasAdded()) {
                    Boolean switchToWelcome = (Boolean) change.getValueAdded();
                    if (switchToWelcome != null && switchToWelcome) {
                        Platform.runLater(this::showWelcomePage);
                    }
                } else if ("switch_to_monitor".equals(change.getKey()) && change.wasAdded()) {
                    Boolean switchToMonitor = (Boolean) change.getValueAdded();
                    if (switchToMonitor != null && switchToMonitor) {
                        Platform.runLater(this::openMonitor);
                    }
                } else if ("current_active_tab".equals(change.getKey()) && change.wasAdded()) {
                    String tabId = String.valueOf(change.getValueAdded());
                    Platform.runLater(() -> monitorSidebarForTab(tabId));
                } else if ("execute_command".equals(change.getKey()) && change.wasAdded()) {
                    String command = String.valueOf(change.getValueAdded());
                    Platform.runLater(() -> executeBookmarkCommand(command));
                }
            });
    }

    @SuppressWarnings("unchecked")
    private void initializeSidebarStorageTable() {
        if (storageTable == null || pathColumn == null || sizeColumn == null) return;
        TableColumn<Map<String, String>, String> path = (TableColumn<Map<String, String>, String>) pathColumn;
        TableColumn<Map<String, String>, String> size = (TableColumn<Map<String, String>, String>) sizeColumn;
        path.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getOrDefault("path", "")));
        size.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getOrDefault("size", "")));
    }

    private void monitorSidebarForTab(String tabId) {
        activeSidebarTabId = tabId;
        if (activeSidebarMonitor != null) activeSidebarMonitor.stopMonitoring();
        var connection = sshService == null ? null : sshService.getTabConnection(tabId);
        if (connection == null || !connection.isConnected()) {
            activeSidebarMonitor = null;
            clearSidebarMetrics();
            return;
        }
        if (statusHealthLabel != null) statusHealthLabel.setText("已连接");
        if (hostStatusDot != null) hostStatusDot.setStyle("-fx-text-fill: -fx-success-color;");
        // 连接参数由 ParameterManager / 连接列表侧更新；此处仅刷新健康状态
        if (statusHostLabel != null && statusHostLabel.getText().equals("—")) {
            statusHostLabel.setText(tabId != null ? tabId : "已连接");
        }
        SystemMonitor monitor = sidebarMonitors.computeIfAbsent(tabId, ignored -> new SystemMonitor(connection));
        activeSidebarMonitor = monitor;
        monitor.setCallback(metrics -> updateSidebarMetrics(tabId, metrics));
        monitor.startMonitoring();
        loadSidebarStorage(tabId, connection);
    }

    private void updateSidebarMetrics(String tabId, com.runzhi.aishell.monitor.domain.SystemMetrics metrics) {
        Platform.runLater(() -> {
            if (!Objects.equals(activeSidebarTabId, tabId) || metrics == null) return;
            uptimeLabel.setText(metrics.getUptime() == null ? "-" : metrics.getUptime());
            loadLabel.setText(String.format("%.2f, %.2f, %.2f", metrics.getLoadAverage1(),
                    metrics.getLoadAverage5(), metrics.getLoadAverage15()));
            cpuLabel.setText(String.format("%.1f%%", metrics.getCpuUser()));
            double memory = metrics.getTotalMemory() > 0
                    ? metrics.getUsedMemory() * 100.0 / metrics.getTotalMemory() : 0;
            double swap = metrics.getTotalSwap() > 0
                    ? metrics.getUsedSwap() * 100.0 / metrics.getTotalSwap() : 0;
            memoryLabel.setText(String.format("%d/%d (%.1f%%)", metrics.getUsedMemory(), metrics.getTotalMemory(), memory));
            swapLabel.setText(String.format("%d/%d (%.1f%%)", metrics.getUsedSwap(), metrics.getTotalSwap(), swap));
        });
    }

    @SuppressWarnings("unchecked")
    private void loadSidebarStorage(String tabId, com.runzhi.aishell.ssh.SSHConnectionManager connection) {
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                var rows = FXCollections.<Map<String, String>>observableArrayList();
                for (String line : connection.executeCommand("LC_ALL=C df -Pk").split("\\R")) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length < 6 || !fields[1].matches("\\d+")) continue;
                    Map<String, String> row = new HashMap<>();
                    row.put("path", fields[fields.length - 1]);
                    row.put("size", formatSidebarKiB(fields[2]) + " / " + formatSidebarKiB(fields[1]));
                    rows.add(row);
                }
                Platform.runLater(() -> {
                    if (Objects.equals(activeSidebarTabId, tabId)) storageTable.setItems(rows);
                });
            } catch (Exception e) {
                log.warn("无法读取 Tab {} 的磁盘信息: {}", tabId, e.getMessage());
            }
        });
    }

    private String formatSidebarKiB(String value) {
        try {
            double kib = Double.parseDouble(value);
            if (kib >= 1024 * 1024) return String.format("%.1f GB", kib / (1024 * 1024));
            if (kib >= 1024) return String.format("%.1f MB", kib / 1024);
            return String.format("%.0f KB", kib);
        } catch (NumberFormatException e) { return value; }
    }

    private void clearSidebarMetrics() {
        if (uptimeLabel != null) uptimeLabel.setText("-");
        if (loadLabel != null) loadLabel.setText("0.00, 0.00, 0.00");
        if (cpuLabel != null) cpuLabel.setText("0%");
        if (memoryLabel != null) memoryLabel.setText("0/0 (0%)");
        if (swapLabel != null) swapLabel.setText("0/0 (0%)");
        if (storageTable != null) storageTable.getItems().clear();
    }

    /**
     * 检查是否是首次启动
     * 通过检查是否有保存的 SSH 连接配置来判断
     */
    private boolean checkFirstLaunch() {
        try {
            // 使用 AIShellModuleManager 的 loadConnections 方法加载连接配置
            List<ConnectionParams> connections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
            
            // 如果没有保存的连接，则是首次启动
            boolean isFirst = (connections == null || connections.isEmpty());
            
            log.info("首次启动检查：{} (已保存 {} 个连接)", 
                    isFirst ? "是" : "否", 
                    connections != null ? connections.size() : 0);
            
            return isFirst;
        } catch (Exception e) {
            log.error("首次启动检查失败：{}", e.getMessage(), e);
            // 出错时默认不是首次启动（保守策略）
            return false;
        }
    }

    /**
     * 显示欢迎页面
     */
    private void showWelcomePage() {
        try {
            log.info("显示欢迎页面");
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/welcome-page.fxml"));
            loader.setResources(I18n.bundle());
            Parent welcomeView = loader.load();
            
            welcomePageController = loader.getController();
            
            // 使用视图切换器加载欢迎页面
            viewSwitcher.replaceContent(welcomeView, "welcome-page");
            
            log.debug("欢迎页面已加载");
        } catch (Exception e) {
            log.error("加载欢迎页面失败：{}", e.getMessage(), e);
            // 如果加载失败，回退到连接列表
            initializeConnectionList();
        }
    }

    public void initializeServices() {
        // 使用模块管理器获取服务
        moduleManager = AIShellModuleManager.getInstance();
        sshService = moduleManager.getSshService();
        aiService = moduleManager.getAiService();
        systemMonitor = moduleManager.getSystemMonitor();


        if (AIConfigManager.getInstance().getCurrentAIAdapter() != null) {
            aiAdapter = AIConfigManager.getInstance().getCurrentAIAdapter();
        }
    }


    // 统一的连接方法 - 合并重复功能
    @FXML
    public void connectToServer() {
        openConnectionList();
        statusLabel.setText("已打开连接管理，请选择要连接的服务器");
    }

    // 统一的断开连接方法 - 合并重复功能
    @FXML
    public void disconnectFromServer() {
        if (systemMonitor != null) {
            systemMonitor.stopMonitoring();
        }
        // sshService 可能在服务初始化完成前被触发，断开前需判空
        if (sshService != null) {
            sshService.disconnect();
        }

        statusLabel.setText(DEFAULT_STATUS_DISCONNECTED);

        // 通知各面板连接已断开
        Platform.runLater(() -> {
            if (terminalPanelController != null) {
                // 终端面板可能需要更新状态
                terminalPanelController.handleDisconnection();
            }
        });
    }

    @FXML
    public void openAIAssistant() {
        // 已在终端时保留当前标签；只有从其他页面进入时才创建终端视图。
        ensureTerminalPanelVisible();
        if (terminalPanelController != null) {
            terminalPanelController.showAgentWorkspace();
        }
        if (statusAiLabel != null) statusAiLabel.setText("Agent");
        statusLabel.setText("已聚焦终端 Agent");
    }

    /** Ctrl+Shift+A 在当前终端视图中切换 Agent 面板。 */
    @FXML
    public void toggleAIPanel() {
        ensureTerminalPanelVisible();
        if (terminalPanelController != null) {
            terminalPanelController.toggleAgentWorkspace();
        }
    }

    /** Keeps AI entry points on the active terminal instance instead of replacing its tabs. */
    private void ensureTerminalPanelVisible() {
        boolean terminalVisible = contentArea != null && contentArea.getChildren().stream()
                .anyMatch(node -> node.getStyleClass().contains("terminal-panel"));
        if (!terminalVisible) {
            switchToTerminalView();
            return;
        }
        if (viewSwitcher != null) {
            TerminalPanelController active = viewSwitcher.getController(
                    "terminal-panel.fxml", TerminalPanelController.class);
            if (active != null) terminalPanelController = active;
        }
    }

    /** V1 §37：隐藏/显示服务器栏 */
    @FXML
    public void toggleServerPanel() {
        serverOpen = !serverOpen;
        applyPanelVisibility();
        LAYOUT_PREFS.putBoolean(KEY_SERVER_OPEN, serverOpen);
        if (serverOpen) {
            loadServerListIntoPanel();
        }
    }

    /** V1：主题切换 深色/白色 */
    @FXML
    public void toggleTheme() {
        try {
            var tm = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            var next = tm.getCurrentTheme() == com.runzhi.aishell.common.util.ThemeManager.Theme.LIGHT
                    ? com.runzhi.aishell.common.util.ThemeManager.Theme.DARK
                    : com.runzhi.aishell.common.util.ThemeManager.Theme.LIGHT;
            Scene scene = contentArea != null ? contentArea.getScene() : null;
            tm.applyTheme(scene, next);
            LAYOUT_PREFS.put(KEY_THEME, next.name());
            refreshThemeLabel();
            statusLabel.setText("主题：" + next.getDisplayName());
        } catch (Exception e) {
            log.error("主题切换失败", e);
        }
    }

    /** V1 §12：底部文件 Drawer（当前标签） */
    @FXML
    public void toggleFilesDrawer() {
        if (terminalPanelController != null) {
            terminalPanelController.toggleFileDrawer();
            statusLabel.setText("文件抽屉已切换");
        } else {
            // 退回到终端视图后再切换
            switchToTerminalView();
            statusLabel.setText("请先打开终端，再使用文件抽屉");
        }
    }

    /** 连接成功后同步状态栏/顶栏目标主机 */
    public void updateCurrentHostDisplay(ConnectionParams params) {
        if (params == null) return;
        String name = (params.getProfileName() != null && !params.getProfileName().isBlank())
                ? params.getProfileName()
                : params.getHost();
        String display = (params.getUsername() != null ? params.getUsername() + "@" : "")
                + params.getHost() + ":" + params.getPort();
        if (currentHostLabel != null) currentHostLabel.setText(name + " · " + display);
        if (statusHostLabel != null) statusHostLabel.setText(name);
        if (statusHealthLabel != null) statusHealthLabel.setText("已连接");
        if (hostStatusDot != null) hostStatusDot.setStyle("-fx-text-fill: -fx-success-color;");
    }

    /** 安装 Agent 面板快捷键，行为与工具栏显隐按钮保持一致。 */
    private void setupV1Shortcuts() {
        if (contentArea == null) {
            return;
        }
        contentArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.isShiftDown()
                    && (e.getCode() == javafx.scene.input.KeyCode.A)) {
                toggleAIPanel();
                e.consume();
            }
        });
    }

    private void applyPanelVisibility() {
        if (serverPanel != null) {
            serverPanel.setVisible(serverOpen);
            serverPanel.setManaged(serverOpen);
        }
        // 右侧 AI 面板已移除
        if (btnToggleServer != null) {
            btnToggleServer.setText(serverOpen ? "服务器" : "服务器 ›");
        }
        if (btnCollapseServer != null) {
            btnCollapseServer.setText(serverOpen ? "‹" : "›");
        }
        if (serverOpen && mainSplitPane != null) {
            double divider = LAYOUT_PREFS.getDouble(KEY_SERVER_DIVIDER, 0.14);
            Platform.runLater(() -> mainSplitPane.setDividerPositions(divider));
        }
    }

    /** Restores and remembers the server navigation column width. */
    private void setupLayoutPersistence() {
        if (mainSplitPane == null || mainSplitPane.getDividers().isEmpty()) return;
        double divider = LAYOUT_PREFS.getDouble(KEY_SERVER_DIVIDER, 0.14);
        if (divider < 0.10 || divider > 0.22) divider = 0.14;
        mainSplitPane.setDividerPositions(divider);
        mainSplitPane.getDividers().get(0).positionProperty().addListener((observable, oldValue, newValue) -> {
            double position = newValue.doubleValue();
            if (serverOpen && position >= 0.10 && position <= 0.22) {
                LAYOUT_PREFS.putDouble(KEY_SERVER_DIVIDER, position);
            }
        });
    }

    private void refreshThemeLabel() {
        try {
            var tm = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            if (btnTheme != null) {
                btnTheme.setText(tm.getCurrentTheme() == com.runzhi.aishell.common.util.ThemeManager.Theme.LIGHT
                        ? "浅色" : "深色");
            }
        } catch (Exception ignored) {
        }
    }

    /** 从 Preferences 恢复主题（默认深色） */
    private void restoreTheme() {
        try {
            String name = LAYOUT_PREFS.get(KEY_THEME, "DARK");
            var tm = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            var theme = switch (name) {
                case "LIGHT" -> com.runzhi.aishell.common.util.ThemeManager.Theme.LIGHT;
                default -> com.runzhi.aishell.common.util.ThemeManager.Theme.DARK;
            };
            Scene scene = contentArea != null ? contentArea.getScene() : null;
            if (scene != null) {
                tm.applyTheme(scene, theme);
            }
        } catch (Exception e) {
            log.warn("恢复主题失败", e);
        }
    }

    /** 将连接配置加载到左侧服务器导航面板（V1 §6） */
    private void loadServerListIntoPanel() {
        if (serverContent == null) {
            return;
        }
        try {
            // ConfigManager 与连接列表同源（loadConnectionConfigs），比 ConnectionConfigManager 更稳
            List<ConnectionParams> connections =
                    com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
            serverContent.getChildren().clear();

            Label title = new Label("已保存连接");
            title.setStyle("-fx-text-fill: -fx-text-secondary; -fx-font-size: 11px; -fx-font-weight: bold;");
            serverContent.getChildren().add(title);

            if (connections == null || connections.isEmpty()) {
                Label empty = new Label("暂无保存的连接\n点击右上「+ 新建连接」创建");
                empty.setWrapText(true);
                empty.setStyle("-fx-text-fill: -fx-text-disabled;");
                serverContent.getChildren().add(empty);
                return;
            }

            for (ConnectionParams conn : connections) {
                Button card = new Button();
                card.setMaxWidth(Double.MAX_VALUE);
                card.getStyleClass().add("server-card");
                String name = (conn.getProfileName() != null && !conn.getProfileName().isBlank())
                        ? conn.getProfileName()
                        : conn.getHost();
                String sub = conn.getUsername() + "@" + conn.getHost() + ":" + conn.getPort();
                card.setText(name + "\n" + sub);
                card.setWrapText(true);
                card.setStyle("-fx-alignment: CENTER_LEFT; -fx-padding: 8;");
                card.setOnAction(e -> {
                    statusLabel.setText("连接 " + name + " …");
                    com.runzhi.aishell.common.util.ParameterManager.getInstance()
                            .put("connection_params", conn, 30000);
                    switchToTerminalViewWithParams();
                    if (currentHostLabel != null) {
                        currentHostLabel.setText(name + " · " + sub);
                    }
                    if (statusHostLabel != null) {
                        statusHostLabel.setText(name);
                    }
                });
                serverContent.getChildren().add(card);
            }

            Label manage = new Label("在顶部「连接」中管理服务器");
            manage.setStyle("-fx-text-fill: -fx-text-disabled; -fx-font-size: 11px;");
            serverContent.getChildren().add(manage);
        } catch (Exception e) {
            log.error("加载服务器列表失败", e);
            Label err = new Label("连接列表加载失败\n请打开顶部「连接」");
            err.setStyle("-fx-text-fill: -fx-danger-color;");
            err.setWrapText(true);
            serverContent.getChildren().setAll(err);
        }
    }

    @FXML
    public void openSettings() {
        SettingsDialogController.showDialog(
                contentArea != null && contentArea.getScene() != null
                        ? contentArea.getScene().getWindow() : null);
    }

    private void openConfiguredStartupPage() {
        String saved = Preferences.userNodeForPackage(SettingsGeneralController.class)
                .get("general.startupPage", StartupPage.CONNECTIONS.label());
        StartupPage page = StartupPage.fromPreference(saved);
        log.info("打开配置的启动页面：{}", page.label());
        switch (page) {
            case TERMINAL -> {
                if (sshService == null && moduleManager != null) sshService = moduleManager.getSshService();
                switchToTerminalView();
            }
            case WELCOME -> showWelcomePage();
            case CONNECTIONS -> initializeConnectionList();
        }
    }

    @FXML
    public void openTerminalSettings() {
        // 打开终端设置对话框，包括主题选择等
        try {
            ShellEnhancementManager enhancementManager = ShellEnhancementManager.getInstance();

            // 获取主题模块实例
            com.runzhi.aishell.terminal.enhancement.modules.EnhancedThemeModule themeModule =
                    com.runzhi.aishell.terminal.enhancement.TerminalEnhancementManager.getInstance()
                            .getEnhancedThemeModule();

            VBox themeSelector = ThemeEnhancer
                    .createThemeSelector(themeModule, null, null);

            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("终端设置");
            dialog.setHeaderText("配置终端外观和行为");

            ButtonType applyButtonType = new ButtonType("应用", ButtonType.OK.getButtonData());
            ButtonType cancelButtonType = new ButtonType("取消", ButtonType.CANCEL.getButtonData());
            dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, cancelButtonType);

            dialog.getDialogPane().setContent(themeSelector);

            dialog.showAndWait();
        } catch (Exception e) {
            log.error("打开终端设置失败", e);
            showAlert("打开终端设置失败：" + e.getMessage());
        }
    }

    @FXML
    public void openNewConnectionConfig() {
        try {
            // 边界检查：检查系统资源
            BoundaryChecker.SystemResourceStatus resourceStatus = BoundaryChecker.checkSystemResources();
            if (!resourceStatus.isHealthy()) {
                log.warn("系统资源不足: {}", resourceStatus);
                showAlert("系统资源不足，请关闭一些程序后再试");
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/new-connection-config-dialog.fxml"));
            loader.setResources(I18n.bundle());
            Parent root = loader.load();

            // 获取控制器并设置SSH服务
            NewConnectionConfigController controller = loader.getController();
            if (controller != null) {
                controller.setSshService((SSHServiceImpl) sshService);
            }

            Stage stage = new Stage();
            stage.setTitle("新建连接配置");
            Scene scene = new Scene(root);
            // 新窗口直接使用当前主题入口，避免依赖模块管理器尚未设置的样式路径。
            var themeManager = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            scene.getStylesheets().setAll(themeManager.getStylesheetsFor(themeManager.getCurrentTheme()));
            stage.setScene(scene);
            stage.show();
        } catch (IOException e) {
            log.error("打开新建连接配置对话框失败", e);
            showAlert("打开新建连接配置对话框失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("打开连接配置时发生未知错误", e);
            showAlert("操作失败: " + e.getMessage());
        }
    }

    // 文件菜单功能
    @FXML
    public void newConnection() {
        // 打开新建连接配置对话框
        openNewConnectionConfig();
    }

    @FXML
    public void openConfig() {
        // 打开配置对话框
        openSettings();
    }

    @FXML
    public void exitApplication() {
        // 退出应用程序
        // 停止所有监控器
        if (monitorPanelController != null) {
            monitorPanelController.stopAllMonitors();
        }
        Platform.exit();
        System.exit(0);
    }

    /**
     * 执行书签/命令模板保存的命令：写入当前选中终端标签的输入框，并发送给该标签绑定的 SSH 会话。
     * 命令始终在当前标签上下文中执行，绝不跨标签复用连接。
     */
    private void executeBookmarkCommand(String command) {
        if (command == null || command.isBlank()) return;
        com.runzhi.aishell.common.ai.AIShellModuleManager manager =
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
        com.runzhi.aishell.service.TerminalTabService tabService = manager.getTerminalTabService();
        com.runzhi.aishell.service.TerminalCommandService commandService = manager.getTerminalCommandService();
        Object pane = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("terminalTabPane");
        if (!(pane instanceof javafx.scene.control.TabPane tabPane) || tabService == null || commandService == null) {
            statusLabel.setText("没有活动的终端，无法执行命令");
            log.warn("书签命令未执行：终端未就绪");
            return;
        }
        javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("没有活动的终端标签，无法执行命令");
            return;
        }
        javafx.scene.control.TextField input = tabService.getTabToInput().get(selected);
        if (input != null) input.setText(command);
        commandService.executeShellCommand(selected, command);
    }

    @FXML
    public void openTerminal() {
        switchToTerminalView();
        statusLabel.setText("终端面板已打开");
    }

    @FXML
    public void openSimpleTerminal() {
        // 创建一个简单的终端标签页，无需SSH连接
        if (terminalPanelController != null) {
            Platform.runLater(() -> {
                terminalPanelController.createSimpleTerminalTab();
            });
        } else {
            log.warn("Terminal panel controller is not initialized");
            statusLabel.setText("终端面板控制器未初始化，请稍后再试");
        }
    }

    @FXML
    public void openFileTransfer() {
        // 绑定当前终端标签的 SSH 上下文，否则新窗口的远程列表与传输没有可用连接。
        final String tabId = terminalPanelController == null ? null : terminalPanelController.getSelectedTabId();
        moduleManager.getNavigationManager().openInNewWindow("file-manager-panel.fxml", "文件管理器",
                controller -> {
                    if (controller instanceof FileManagerPanelController fileManager) {
                        fileManager.setSshService(sshService, tabId);
                    }
                });
    }

    @FXML
    public void openMonitor() {
        // 切换到监控面板
        viewSwitcher.switchTo("monitor-panel.fxml", "white-background",
                MonitorPanelController.class, controller -> {
                    if (sshService instanceof SSHServiceImpl) {
                        controller.setSshService((SSHServiceImpl) sshService);
                    }
                });
    }

    @FXML
    public void openAdvancedMonitorCharts() {
        moduleManager.getNavigationManager().openInNewWindow("advanced-monitor-charts.fxml", "高级监控图表");
    }

    @FXML
    public void openBookmarkPanel() {
        moduleManager.getNavigationManager().openInNewWindow("bookmark-panel.fxml", "书签面板");
    }

    @FXML
    public void openCommandHistory() {
        final String tabId = terminalPanelController == null ? null : terminalPanelController.getSelectedTabId();
        moduleManager.getNavigationManager().openInNewWindow("command-history-dialog.fxml", "命令历史",
                controller -> {
                    // 传入当前标签页，使对话框展示该标签页的命令历史；
                    // 无标签页时由控制器回退到 "default"
                    if (tabId != null && controller instanceof CommandHistoryDialogController historyController) {
                        historyController.setCurrentTabId(tabId);
                    }
                });
    }

    @FXML
    public void openAbout() {
        // 显示关于对话框
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(ABOUT_DIALOG_TITLE);
            alert.setHeaderText(ABOUT_DIALOG_HEADER);
            String fullContent = ABOUT_DIALOG_CONTENT + "\n\n联系方式: support@aishell.com";
            alert.setContentText(fullContent);

            // 设置对话框样式
            DialogPane dialogPane = alert.getDialogPane();
            UITools.setDialogCSS(dialogPane);

            alert.showAndWait();
        });
    }

    /**
     * 显示自愿赞助对话框。
     * <p>
     * 按「开源与自愿赞助开发方案」阶段 B：仅提供低干扰入口与静态收款码，
     * 不收集任何信息、不影响功能可用性，赞助不解锁额外功能。
     */
    @FXML
    public void openDonation() {
        Platform.runLater(() -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("支持项目");
            UITools.setDialogCSS(dialog.getDialogPane());

            Label intro = new Label(
                    "AIShell 采用 GNU GPL v3.0 开源，个人和企业均可免费用于商业及非商业用途；修改和分发须遵守许可证。\n"
                            + "如果它帮助你节省了时间，欢迎自愿赞助，支持后续开发与维护。\n"
                            + "不赞助也可以使用全部已完成的功能；AI 服务由你自行配置，相关费用以所选服务为准。");
            intro.setWrapText(true);
            intro.setMaxWidth(560);

            HBox qrBox = new HBox(28,
                    qrBlock("微信支付", "donations/wechat-pay.jpg"),
                    qrBlock("支付宝", "donations/alipay.jpg"));
            qrBox.setAlignment(Pos.CENTER);
            qrBox.setPadding(new Insets(10, 0, 4, 0));

            VBox content = new VBox(14, intro, qrBox);
            content.setAlignment(Pos.CENTER);
            content.setPrefWidth(600);

            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.showAndWait();
        });
    }

    /** 构建「标题 + 收款码」垂直区块；图片缺失时降级为提示文字，不展示空白占位。 */
    private VBox qrBlock(String title, String resource) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 13; -fx-font-weight: bold; -fx-text-alignment: center;");
        VBox block = new VBox(6, titleLabel);
        block.setAlignment(Pos.CENTER);
        try (InputStream in = MainViewController.class
                .getResourceAsStream("/com/runzhi/aishell/" + resource)) {
            if (in != null) {
                ImageView view = new ImageView(new Image(in));
                view.setFitWidth(220);
                view.setFitHeight(220);
                view.setPreserveRatio(true);
                block.getChildren().add(view);
            } else {
                block.getChildren().add(new Label("（图片未找到）"));
            }
        } catch (IOException e) {
            log.warn("加载收款码失败: {}", resource, e);
            block.getChildren().add(new Label("（图片加载失败）"));
        }
        return block;
    }

    // 对每个已有 SSH 连接参数的终端标签分别发起重连。
    @FXML
    public void reconnectAllTerminals() {
        ensureTerminalPanelVisible();
        int count = terminalPanelController == null ? 0
                : terminalPanelController.attemptReconnectionForAllSshTabs();
        if (count == 0) {
            statusLabel.setText("没有可重连的 SSH 终端");
        } else {
            statusLabel.setText("已对 " + count + " 个 SSH 终端发起重连");
        }
    }

    private void showAlert(String message) {
        DialogUtils.showInfoDialog("提示", null, message);
    }

    /**
     * 使用指定的连接参数进行连接
     */
    public void connectWithParams(ConnectionParams params) {
        try {
            // 参数验证
            InputValidator.validateNotNull(params, "连接参数");
            InputValidator.validateHostname(params.getHost(), "主机名");
            InputValidator.validatePort(params.getPort(), "端口");
            InputValidator.validateUsername(params.getUsername(), "用户名");

            // 边界检查
            BoundaryChecker.checkConnectionCount(1, 100, "连接数");

            // 确保SSH服务已初始化
            if (sshService == null) {
                sshService = new SSHServiceImpl();
            }

            statusLabel.setText(DEFAULT_STATUS_CONNECTING + params.getHost() + ":" + params.getPort() + " - " + params.getUsername());

            // 使用模块管理器传递连接参数
            com.runzhi.aishell.common.util.ParameterManager.getInstance().put("connection_params", params, 30000); // 30 秒超时

            // 切换到终端页面，替换连接列表
            switchToTerminalViewWithParams();

        } catch (Exception e) {
            log.error("连接参数验证失败", e);
            statusLabel.setText("连接参数验证失败：" + e.getMessage());
            showAlert("连接失败：" + e.getMessage());
        }
    }

    /**
     * 切换到终端视图
     */
    private void switchToTerminalView() {
        viewSwitcher.switchTo("terminal-panel.fxml", "terminal-panel",
                TerminalPanelController.class, controller -> {
                    controller.setSshService(sshService);
                    this.terminalPanelController = controller;
                    controller.createNewTerminalTab();
                });
        updateWorkModeIndicators("terminal");
    }

    /**
     * 切换到终端视图并创建带参数的Tab
     */
    private void switchToTerminalViewWithParams() {
        ConnectionParams params = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("connection_params", ConnectionParams.class);

        viewSwitcher.switchTo("terminal-panel.fxml", "terminal-panel");

        // 延迟获取控制器并创建标签页
        viewSwitcher.delayedAction(500, () -> {
            TerminalPanelController terminalController = viewSwitcher.getController(
                    "terminal-panel.fxml", TerminalPanelController.class);

            if (terminalController != null) {
                terminalController.setSshService(sshService);
                this.terminalPanelController = terminalController;

                Platform.runLater(() -> {
                    if (params != null) {
                        terminalController.createNewTerminalTabWithParams(params);
                    } else {
                        terminalController.createNewTerminalTab();
                    }
                });
            } else {
                log.error("TerminalPanelController not available after delay");
            }
        });
    }

    /**
     * 直接使用参数切换到终端视图并创建带参数的Tab
     */
    private void switchToTerminalViewWithParamsDirect(ConnectionParams params) {
        viewSwitcher.switchTo("terminal-panel.fxml", "terminal-panel",
                TerminalPanelController.class, controller -> {
                    controller.setSshService(sshService);
                    this.terminalPanelController = controller;
                    Platform.runLater(() -> {
                        if (params != null) {
                            controller.createNewTerminalTabWithParams(params);
                        } else {
                            controller.createNewTerminalTab();
                        }
                    });
                });
        updateWorkModeIndicators("terminal");
        if (params != null) {
            String host = params.getHost();
            String user = params.getUsername();
            String display = (user != null ? user + "@" : "") + host;
            if (currentHostLabel != null) {
                currentHostLabel.setText(display);
            }
            if (statusHostLabel != null) {
                statusHostLabel.setText(params.getProfileName() != null
                        ? params.getProfileName() : host);
            }
            if (statusHealthLabel != null) {
                statusHealthLabel.setText("连接中");
            }
        }
    }

    /** V1 状态栏指标同步 */
    private void updateWorkModeIndicators(String mode) {
        if ("terminal".equals(mode)) {
            if (statusTerminalTab != null) statusTerminalTab.setText("Terminal");
            if (statusHealthLabel != null && "就绪".equals(statusHealthLabel.getText())) {
                statusHealthLabel.setText("工作区");
            }
            if (navTerminal != null) navTerminal.getStyleClass().add("active");
        }
    }

    /**
     * 初始化连接列表面板
     */
    private void initializeConnectionList() {
        viewSwitcher.switchTo("connection-list.fxml", "white-background",
                ConnectionListController.class, controller -> {
                    // 保存引用，使文件菜单的导入/导出连接能委托到连接列表控制器
                    // （此前该字段从未赋值，handleImport/handleExportConnections 永远走 null 分支）
                    MainViewController.this.connectionListController = controller;
                    controller.setSshService(sshService);
                    controller.initialize();
                });
    }

    /** 导航栏「连接」按钮入口（FXML 需要 @FXML 可访问方法） */
    @FXML
    public void openConnectionList() {
        initializeConnectionList();
    }

    /**
     * 更新内容面板而不改变窗口
     */
    public void updateContentPane(String fxmlPath) {
        if (moduleManager != null && contentArea != null) {
            moduleManager.getNavigationManager().updateContentPane(fxmlPath, contentArea);
        }
    }

    @FXML
    public void openSystemInfo() {
        try {
            String sysInfo = SystemInfoProvider.getSystemInfo();

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("系统信息");
            alert.setHeaderText("系统详细信息");
            alert.setContentText(sysInfo);
            alert.showAndWait();
        } catch (Exception e) {
            log.error("Failed to get system info", e);
            showAlert("获取系统信息失败: " + e.getMessage());
        }
    }

    @FXML
    public void handleImportConnections() {
        // 导入连接配置
        if (connectionListController != null) {
            connectionListController.handleImportConnections();
        } else {
            log.warn("Connection list controller is not initialized");
            statusLabel.setText("连接列表控制器未初始化");
        }
    }

    @FXML
    public void handleExportConnections() {
        // 导出连接配置
        if (connectionListController != null) {
            connectionListController.handleExportConnections();
        } else {
            log.warn("Connection list controller is not initialized");
            statusLabel.setText("连接列表控制器未初始化");
        }
    }
}
