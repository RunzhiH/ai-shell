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
import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.animation.PauseTransition;
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
import java.util.LinkedHashMap;
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
    private static final String DEFAULT_STATUS_DISCONNECTED = "已断开";
    private static final String ABOUT_DIALOG_TITLE = "关于 AIShell";
    private static final String ABOUT_DIALOG_HEADER = "AIShell - SSH & AI Assistant";
    private static final String ABOUT_DIALOG_CONTENT = "版本: " + AppConstants.APP_VERSION + "\n这是一个集成了SSH连接和AI助手功能的桌面应用。\n\n作者: Runzhi\n\n基于JavaFX开发";

    @FXML
    private Label statusLabel;

    // ===== V1 主布局：Server | Terminal（Agent 在终端内；无右侧 AI 面板） =====
    @FXML
    private javafx.scene.layout.VBox serverPanel;
    @FXML
    private javafx.scene.layout.HBox serverPanelHead;
    @FXML
    private Label serverPanelHint;
    @FXML
    private Separator serverPanelSeparator;
    @FXML
    private javafx.scene.control.TreeView<ConnectionParams> serverTree;
    @FXML
    private javafx.scene.control.TextField serverTreeFilter;
    /** 活动会话身份与指标独立滚动视口；服务器树保持自己的滚动与高度。 */
    @FXML
    private ScrollPane activeSessionScroll;
    @FXML
    private Label activeSessionTitle;
    @FXML
    private Label activeSessionName;
    /** 侧栏指标组（CPU/内存/负载/网络 + 更多系统信息）；身份标题不属于该组。 */
    @FXML
    private VBox metricsGroup;
    @FXML
    private Label netLabel;
    @FXML
    private javafx.scene.control.Label statusApprovalLocator;
    @FXML
    private Label currentHostLabel;
    @FXML
    private Label hostStatusDot;
    /**
     * 会话状态圆点的内联样式。
     * <p>内联样式里的颜色变量只能引用主题样式表中已定义的名字（style.css / light-theme.css
     * 的 .root 块），否则 JavaFX 解析时会打印 "Could not resolve ..." 警告且颜色不生效。</p>
     */
    static final String STATUS_DOT_CONNECTED_STYLE = "-fx-text-fill: -fx-success-color;";
    static final String STATUS_DOT_IDLE_STYLE = "-fx-text-fill: -fx-text-secondary;";
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
    private Label statusPageSeparator;

    @FXML
    private javafx.scene.control.Button navConnections;
    @FXML
    private javafx.scene.control.Button navTerminal;
    @FXML
    private javafx.scene.control.Button navFiles;
    @FXML
    private javafx.scene.control.Button navMonitor;
    @FXML
    private javafx.scene.control.Button btnCommandPalette;
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
    /** 活动会话滚动块最多占侧栏高度 50%，其余高度优先留给服务器树。 */
    private static final double ACTIVE_SESSION_MAX_HEIGHT_RATIO = 0.50;
    private boolean serverOpen = true;
    /** 当前内容页是否为终端工作区；侧栏指标只在这一页有意义。 */
    private boolean terminalContext;

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

    // 命令面板（点击式，无全局快捷键）
    private com.runzhi.aishell.ui.CommandPalette commandPalette;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 调用父类初始化方法，确保parameterManager等字段被正确初始化
        super.initialize(location, resources);

        if (statusLabel != null) {
            statusLabel.setText(I18n.tr("main.ready"));
        }

        // 初始化模块管理器（替代之前的pageManager）
        moduleManager = AIShellModuleManager.getInstance();
        sshService = moduleManager.getSshService();
        // 会话状态只有一个来源：连接/断开/关闭标签后统一刷新，界面各处只做展示
        if (sshService != null) {
            sshService.addSessionStateListener(() -> Platform.runLater(this::refreshSessionIndicators));
        }
        initializeSidebarStorageTable();

        // 初始化按钮管理器
        buttonManager = ButtonManager.getInstance();

        // 初始化视图切换器
        viewSwitcher = new com.runzhi.aishell.common.util.ViewSwitcher(contentArea);

        // V1：默认深色；无右侧 AI 面板；Agent 在终端内
        serverOpen = LAYOUT_PREFS.getBoolean(KEY_SERVER_OPEN, true);
        setupLayoutPersistence();
        applyPanelVisibility();
        configureSidebarScroll();
        setupServerTree();
        buildServerTree("");
        setupV1Shortcuts();
        restoreTheme();
        refreshThemeLabel();

        // Agent 会话状态变化 → 底栏审批定位与树角标（归属到具体会话）
        AgentSessionTracker.getInstance().addListener(state -> Platform.runLater(() -> {
            updateApprovalLocator();
            buildServerTree(serverTreeFilter == null ? "" : serverTreeFilter.getText());
        }));
        // 连接参数到达时不直接写目标：此时新标签可能尚未创建/选中，提前写入会把上一台主机标成“已连接”。
        // 当前目标统一从当前选中标签派生，等标签切换或会话刷新时再更新。
        com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters()
            .addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                if ("connection_params".equals(change.getKey()) && change.wasAdded()) {
                    Platform.runLater(this::refreshCurrentTargetDisplay);
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
                    Platform.runLater(() -> {
                        // 连接完成事件可能晚于用户切页；事件不再指向当前标签时不能覆盖当前目标/侧栏
                        if (terminalPanelController != null
                                && !Objects.equals(terminalPanelController.getSelectedTabId(), tabId)) {
                            return;
                        }
                        monitorSidebarForTab(tabId);
                        refreshCurrentTargetDisplay();
                    });
                } else if ("execute_command".equals(change.getKey()) && change.wasAdded()) {
                    String command = String.valueOf(change.getValueAdded());
                    Platform.runLater(() -> executeBookmarkCommand(command));
                }
            });
        refreshFileEntryState();
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
        refreshSidebarMetricsVisibility();
        if (activeSidebarMonitor != null) activeSidebarMonitor.stopMonitoring();
        var connection = (sshService == null || tabId == null) ? null : sshService.getTabConnection(tabId);
        // 活动会话卡标题始终跟随当前活动 tab（指标/文件归属同一会话）
        if (activeSessionTitle != null) {
            String display = terminalPanelController == null ? (tabId == null ? "—" : tabId)
                    : terminalPanelController.tabDisplayFor(tabId);
            activeSessionTitle.setText(I18n.tr("main.activeSession", display));
        }
        if (connection == null || !connection.isConnected()) {
            activeSidebarMonitor = null;
            clearSidebarMetrics();
            // 连接异步建立：绑定瞬间往往未就绪，就绪后重试，避免活动会话卡在「未连接」
            awaitSidebarConnection(tabId);
            return;
        }
        var params = sshService.getTabConnectionParams(tabId);
        if (activeSessionName != null && params != null) {
            activeSessionName.setText((params.getUsername() == null ? "" : params.getUsername() + "@")
                    + params.getHost() + ":" + params.getPort());
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
            if (netLabel != null) {
                String down = metrics.getNetDown() == null ? "—" : metrics.getNetDown();
                String up = metrics.getNetUp() == null ? "—" : metrics.getNetUp();
                netLabel.setText("↓" + down + " ↑" + up);
            }
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

    /** 等待目标会话的 SSH 连接就绪后重试侧栏绑定（约 12 秒内每 600ms 一次）。 */
    private void awaitSidebarConnection(String tabId) {
        if (tabId == null || sshService == null) return;
        final int[] attempts = { 0 };
        retrySidebarOnce(tabId, attempts);
    }

    private void retrySidebarOnce(String tabId, int[] attempts) {
        PauseTransition pause = new PauseTransition(javafx.util.Duration.millis(600));
        pause.setOnFinished(event -> {
            if (!java.util.Objects.equals(tabId, activeSidebarTabId)) return; // 用户已切到别的会话
            attempts[0]++;
            var connection = sshService.getTabConnection(tabId);
            if (connection != null && connection.isConnected()) {
                monitorSidebarForTab(tabId);
            } else if (attempts[0] < 20) {
                retrySidebarOnce(tabId, attempts);
            }
        });
        pause.play();
    }

    private void clearSidebarMetrics() {
        if (uptimeLabel != null) uptimeLabel.setText("-");
        if (loadLabel != null) loadLabel.setText("0.00, 0.00, 0.00");
        if (cpuLabel != null) cpuLabel.setText("0%");
        if (memoryLabel != null) memoryLabel.setText("0/0 (0%)");
        if (swapLabel != null) swapLabel.setText("0/0 (0%)");
        if (netLabel != null) netLabel.setText("—");
        if (activeSessionName != null) activeSessionName.setText(I18n.tr("main.disconnected"));
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
            setPageContext(I18n.tr("main.welcome"), false);
            
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
        statusLabel.setText(I18n.tr("main.selectConnection"));
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

        statusLabel.setText(I18n.tr("main.disconnected"));

        // 通知各面板连接已断开
        Platform.runLater(() -> {
            if (terminalPanelController != null) {
                // 终端面板可能需要更新状态
                terminalPanelController.handleDisconnection();
            }
        });
    }

    /** Independent AI chat keeps the current page and terminal sessions intact. */
    @FXML
    public void openAIAssistant() {
        if (moduleManager == null) moduleManager = AIShellModuleManager.getInstance();
        moduleManager.getNavigationManager().openInNewWindow("ai-assistant-panel.fxml",
                I18n.tr("workbench.aiAssistant"));
    }

    /** Agent tasks stay in the terminal workspace and retain their session ownership. */
    @FXML
    public void openAgentWorkspace() {
        ensureTerminalPanelVisible();
        if (terminalPanelController != null) terminalPanelController.showAgentWorkspace();
        if (statusAiLabel != null) statusAiLabel.setText("Agent");
        if (statusLabel != null) statusLabel.setText(I18n.tr("workbench.agent.focused"));
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
            if (active != null) attachTerminalPanel(active);
        }
    }

    /** 统一挂接终端控制器与其文件区状态回调，避免各入口漏掉底栏状态同步。 */
    private void attachTerminalPanel(TerminalPanelController controller) {
        this.terminalPanelController = controller;
        if (controller != null) controller.setFileAreaStateListener(this::refreshFileEntryState);
    }

    /** V1 §37：隐藏/显示服务器栏 */
    @FXML
    public void toggleServerPanel() {
        serverOpen = !serverOpen;
        applyPanelVisibility();
        LAYOUT_PREFS.putBoolean(KEY_SERVER_OPEN, serverOpen);
        if (serverOpen) {
            buildServerTree(serverTreeFilter == null ? "" : serverTreeFilter.getText());
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
            statusLabel.setText(I18n.tr("main.themeChanged", I18n.tr(next == com.runzhi.aishell.common.util.ThemeManager.Theme.LIGHT ? "main.light" : "main.dark")));
        } catch (Exception e) {
            log.error("主题切换失败", e);
        }
    }

    /**
     * 顶栏、菜单与命令面板的统一文件入口：进入终端工作区并幂等展开当前标签文件区。
     * 已展开时再次进入保持展开，不把主入口做成“开/关”开关。
     */
    @FXML
    public void openFiles() {
        ensureTerminalPanelVisible();
        updateWorkModeIndicators("terminal");
        if (terminalPanelController == null) {
            statusLabel.setText(fileAreaUnavailableReason());
            refreshFileEntryState();
            return;
        }
        TerminalPanelController.DrawerResult result = terminalPanelController.showFileDrawer();
        statusLabel.setText(result == TerminalPanelController.DrawerResult.OPENED
                ? I18n.tr("main.filesOpened") : fileAreaUnavailableReason());
        refreshFileEntryState();
    }

    /** 底栏文件入口：展开/收起当前标签文件区，按钮文本与 tooltip 反映真实状态。 */
    @FXML
    public void toggleFilesDrawer() {
        ensureTerminalPanelVisible();
        if (terminalPanelController == null) {
            statusLabel.setText(fileAreaUnavailableReason());
            refreshFileEntryState();
            return;
        }
        TerminalPanelController.DrawerResult result = terminalPanelController.toggleFileDrawer();
        statusLabel.setText(switch (result) {
            case OPENED -> I18n.tr("main.filesOpened");
            case CLOSED -> I18n.tr("main.filesClosed");
            case UNAVAILABLE -> fileAreaUnavailableReason();
        });
        refreshFileEntryState();
    }

    /** 入口禁用原因只陈述当前标签事实，不把本地标签误报成可展开的远程文件区。 */
    private String fileAreaUnavailableReason() {
        if (terminalPanelController == null) return I18n.tr("main.filesNoTerminal");
        if (terminalPanelController.getSelectedTabId() == null) return I18n.tr("main.filesNoTab");
        if (terminalPanelController.getSelectedConnectionParams() == null) return I18n.tr("main.filesLocalTab");
        return I18n.tr("main.filesUnavailable");
    }

    /** 底栏入口随当前标签状态置灰；禁用时文本与 tooltip 仍给出可见原因。 */
    private void refreshFileEntryState() {
        if (btnFilesDrawer == null) return;
        TerminalPanelController.FileAreaState state = terminalPanelController == null
                ? TerminalPanelController.FileAreaState.UNAVAILABLE
                : terminalPanelController.fileAreaState();
        boolean available = state != TerminalPanelController.FileAreaState.UNAVAILABLE;
        btnFilesDrawer.setDisable(!available);
        String text;
        String help;
        if (!available) {
            text = I18n.tr("main.filesUnavailableShort");
            help = fileAreaUnavailableReason();
        } else {
            boolean open = state == TerminalPanelController.FileAreaState.OPEN;
            text = I18n.tr(open ? "main.filesOpen" : "main.filesClosedState");
            help = I18n.tr(open ? "main.filesCollapseTip" : "main.filesExpandTip");
        }
        btnFilesDrawer.setText(text);
        btnFilesDrawer.setTooltip(new Tooltip(help));
        btnFilesDrawer.setAccessibleHelp(help);
        if (navFiles != null) {
            navFiles.setDisable(!available);
            navFiles.setTooltip(new Tooltip(available ? I18n.tr("main.filesExpandTip") : help));
            navFiles.setAccessibleHelp(help);
        }
    }

    /** 把连接参数写入顶栏/底栏目标；顶栏只放短标识，完整目标放 tooltip。健康状态由当前标签的会话单独刷新。 */
    public void updateCurrentHostDisplay(ConnectionParams params) {
        if (params == null) {
            clearCurrentTargetDisplay();
            return;
        }
        String profileName = (params.getProfileName() != null && !params.getProfileName().isBlank())
                ? params.getProfileName()
                : null;
        String display = (params.getUsername() != null ? params.getUsername() + "@" : "")
                + params.getHost() + ":" + params.getPort();
        String shortLabel = profileName != null && profileName.length() <= 28 ? profileName : display;
        if (currentHostLabel != null) {
            currentHostLabel.setText(shortLabel);
            currentHostLabel.setTooltip(new Tooltip(profileName == null ? display : profileName + " · " + display));
        }
        if (statusHostLabel != null) statusHostLabel.setText(profileName != null ? profileName : display);
    }

    /**
     * 当前目标只从「当前选中的终端标签 + 该标签的 SSH 参数」派生：
     * A/B 标签切换、切本地/提示标签都在这里统一刷新；没有 SSH 目标时清掉旧主机与 tooltip。
     * 异步连接完成事件先在监听处校验仍是当前标签，避免旧会话把目标覆盖成非当前主机。
     */
    private void refreshCurrentTargetDisplay() {
        String tabId = terminalPanelController == null ? null : terminalPanelController.getSelectedTabId();
        if (tabId == null) {
            clearCurrentTargetDisplay();
            // 关闭最后一个会话不会有 current_active_tab 事件，这里同步清掉侧栏活动卡
            if (activeSidebarTabId != null) monitorSidebarForTab(null);
            refreshFileEntryState();
            refreshSidebarMetricsVisibility();
            return;
        }
        ConnectionParams params = selectedTabConnectionParams(tabId);
        if (params == null) {
            clearCurrentTargetDisplay();
            refreshFileEntryState();
            refreshSidebarMetricsVisibility();
            return;
        }
        updateCurrentHostDisplay(params);
        refreshConnectionHealthIndicator();
        refreshFileEntryState();
        refreshSidebarMetricsVisibility();
    }

    /** 选中标签上登记的连接参数优先；SSHService 注册表作为兜底。 */
    private ConnectionParams selectedTabConnectionParams(String tabId) {
        if (terminalPanelController != null) {
            ConnectionParams stored = terminalPanelController.getSelectedConnectionParams();
            if (stored != null) return stored;
        }
        return sshService == null || tabId == null ? null : sshService.getTabConnectionParams(tabId);
    }

    private void clearCurrentTargetDisplay() {
        if (currentHostLabel != null) {
            currentHostLabel.setText(I18n.tr("main.disconnected"));
            currentHostLabel.setTooltip(new Tooltip(I18n.tr("main.disconnected")));
        }
        if (statusHostLabel != null) statusHostLabel.setText("—");
        if (statusHealthLabel != null) statusHealthLabel.setText(I18n.tr("main.disconnected"));
        if (hostStatusDot != null) hostStatusDot.setStyle(STATUS_DOT_IDLE_STYLE);
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
            // SplitPane retains item slots even for unmanaged children; detach the item to release its width.
            if (mainSplitPane != null) {
                if (serverOpen && !mainSplitPane.getItems().contains(serverPanel)) {
                    mainSplitPane.getItems().add(0, serverPanel);
                    setupLayoutPersistence();
                } else if (!serverOpen) {
                    mainSplitPane.getItems().remove(serverPanel);
                }
            }
            serverPanel.setVisible(serverOpen);
            serverPanel.setManaged(serverOpen);
        }
        // 右侧 AI 面板已移除
        if (btnToggleServer != null) {
            btnToggleServer.setText(serverOpen ? I18n.tr("main.servers") : I18n.tr("main.serversClosed"));
        }
        if (btnCollapseServer != null) {
            btnCollapseServer.setText(serverOpen ? "‹" : "›");
        }
        if (serverOpen && mainSplitPane != null) {
            double divider = LAYOUT_PREFS.getDouble(KEY_SERVER_DIVIDER, 0.14);
            Platform.runLater(() -> {
                if (serverOpen && mainSplitPane.getItems().contains(serverPanel))
                    mainSplitPane.setDividerPositions(divider);
            });
        }
    }

    /**
     * 给活动会话块独立的有限视口：内容自然高度超出时可内部滚动，
     * 同时按侧栏高度 50% 与服务器树最小高度双重封顶，短窗口不会把树挤没。
     * 包级可见，供真实 FXML 布局测试在不执行 initialize 时复用。
     */
    void configureSidebarScroll() {
        if (activeSessionScroll == null || serverPanel == null || serverTree == null) return;
        activeSessionScroll.setFitToWidth(true);
        activeSessionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        activeSessionScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        activeSessionScroll.setMinHeight(0);
        if (activeSessionScroll.getContent() instanceof javafx.scene.layout.Region content) {
            // 内容按自然高度排布，不被滚动视口压扁。
            content.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        java.util.List<javafx.beans.Observable> dependencies = new java.util.ArrayList<>();
        dependencies.add(serverPanel.heightProperty());
        dependencies.add(serverPanel.widthProperty());
        if (serverPanelHead != null) dependencies.add(serverPanelHead.heightProperty());
        if (serverTreeFilter != null) dependencies.add(serverTreeFilter.heightProperty());
        if (serverPanelHint != null) dependencies.add(serverPanelHint.heightProperty());
        if (serverPanelSeparator != null) dependencies.add(serverPanelSeparator.heightProperty());
        dependencies.add(serverTree.minHeightProperty());

        activeSessionScroll.maxHeightProperty().bind(
                javafx.beans.binding.Bindings.createDoubleBinding(() -> {
                    double fixed = 8 + regionHeight(serverPanelHead)
                            + regionHeight(serverTreeFilter)
                            + regionHeight(serverPanelHint)
                            + regionHeight(serverPanelSeparator)
                            + serverPanel.getInsets().getTop()
                            + serverPanel.getInsets().getBottom();
                    double treeMin = Math.max(120, serverTree.getMinHeight());
                    double panelHeight = serverPanel.getHeight();
                    double ratioCap = panelHeight * ACTIVE_SESSION_MAX_HEIGHT_RATIO;
                    double treeCap = panelHeight - fixed - treeMin;
                    return Math.max(0, Math.min(ratioCap, treeCap));
                }, dependencies.toArray(new javafx.beans.Observable[0])));
    }

    private static double regionHeight(javafx.scene.layout.Region region) {
        return region == null ? 0 : Math.max(0, region.getHeight());
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
                        ? I18n.tr("main.light") : I18n.tr("main.dark"));
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
                refreshThemeLabel();
            } else if (contentArea != null) {
                // initialize() runs before the FXML root is attached to its Scene.
                // Apply the persisted theme once the Scene becomes available.
                contentArea.sceneProperty().addListener((obs, oldScene, newScene) -> {
                    if (newScene != null) {
                        tm.applyTheme(newScene, theme);
                        refreshThemeLabel();
                    }
                });
            }
        } catch (Exception e) {
            log.warn("恢复主题失败", e);
        }
    }

    /**
     * 服务器会话树（v6 阶段 2）：单击只选中，双击 / Enter 激活会话，筛选即时重建。
     * 取代旧的服务器卡片：卡片单击即连接，无法区分「选中」与「激活」。
     */
    private void setupServerTree() {
        if (serverTree == null) return;
        serverTree.setShowRoot(false);
        serverTree.setRoot(new javafx.scene.control.TreeItem<>());
        serverTree.setCellFactory(tree -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(ConnectionParams item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (item == null) {
                    // 分组头：名称与计数在构建树时挂在 TreeItem 的 graphic（Label）上。
                    // 这里不得 setGraphic(null) 清掉，否则分组头渲染成空白行。
                    setText(null);
                    setGraphic(getTreeItem() != null ? getTreeItem().getGraphic() : null);
                    return;
                }
                setText(null);
                boolean connected = isConnectedSession(item);
                boolean agentOn = hasAgentActivity(item);
                Label name = new Label(connectionDisplayName(item) + (agentOn ? " · Agent" : ""));
                name.getStyleClass().add("server-node-name");
                Label sub = new Label(item.getHost() + ":" + item.getPort()
                        + " · " + (connected ? I18n.tr("main.online") : I18n.tr("main.disconnected")));
                sub.getStyleClass().add("server-node-sub");
                VBox box = new VBox(1, name, sub);
                box.getStyleClass().add("server-node");
                setGraphic(box);
            }
        });
        // 单击只选中是 TreeView 默认行为；双击才激活
        serverTree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                activateSelectedTreeConnection();
            }
        });
        // Enter 激活（消费掉，避免触发分组折叠）
        serverTree.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (activateSelectedTreeConnection()) event.consume();
            }
        });
        if (serverTreeFilter != null) {
            serverTreeFilter.textProperty().addListener((_, _, value) ->
                    buildServerTree(value == null ? "" : value));
        }
    }

    /** 当前树选中的连接（无选中或选中分组头时返回 null）。单击只选中：不产生任何连接动作。 */
    ConnectionParams selectedTreeConnection() {
        if (serverTree == null) return null;
        javafx.scene.control.TreeItem<ConnectionParams> selected = serverTree.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getValue();
    }

    boolean activateSelectedTreeConnection() {
        javafx.scene.control.TreeItem<ConnectionParams> selected = serverTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null) return false;
        activateTreeConnection(selected.getValue());
        return true;
    }

    /**
     * 激活会话：已开标签则选中聚焦，否则新建标签并连接。
     * 单击树节点不会走到这里——树选择不改变活动会话。
     */
    private void activateTreeConnection(ConnectionParams params) {
        // Direct activation owns this request; broadcasting it would also trigger the terminal's deferred creator.
        ensureTerminalPanelVisible();
        if (terminalPanelController != null) {
            terminalPanelController.activateConnection(params);
        }
        refreshCurrentTargetDisplay();
        if (statusLabel != null) statusLabel.setText(I18n.tr("main.activated", connectionDisplayName(params)));
    }

    /** 连接是否在线：与连接列表、底栏共用 SSH 服务的同一份会话记录。 */
    private boolean isConnectedSession(ConnectionParams params) {
        return params != null && sshService != null && sshService.activeSessionCount(params) > 0;
    }

    /** 该连接是否存在运行中或待审批的 Agent 任务（树节点角标依据）。 */
    private boolean hasAgentActivity(ConnectionParams params) {
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();
        for (String sessionId : tracker.runningSessions()) {
            if (sessionOwnsConnection(sessionId, params)) return true;
        }
        for (String sessionId : tracker.sessionsWithPending()) {
            if (sessionOwnsConnection(sessionId, params)) return true;
        }
        return false;
    }

    private boolean sessionOwnsConnection(String sessionId, ConnectionParams params) {
        if (terminalPanelController == null) return false;
        for (javafx.scene.control.Tab tab : terminalPanelController.getTerminalTabs()) {
            if (!sessionId.equals(tab.getId())) continue;
            Object stored = tab.getProperties().get("connectionParams");
            return stored instanceof ConnectionParams candidate
                    && connectionKeyOf(candidate).equals(connectionKeyOf(params));
        }
        return false;
    }

    private String connectionKeyOf(ConnectionParams params) {
        if (params == null || params.getHost() == null) return "";
        return (params.getUsername() == null ? "" : params.getUsername())
                + '@' + params.getHost() + ':' + params.getPort();
    }

    private String connectionDisplayName(ConnectionParams params) {
        if (params == null) return "";
        return (params.getProfileName() != null && !params.getProfileName().isBlank())
                ? params.getProfileName() : params.getHost() + ":" + params.getPort();
    }

    /** 按筛选文本重建会话树；空文本展示全部分组。 */
    private void buildServerTree(String filter) {
        if (serverTree == null) return;
        List<ConnectionParams> connections;
        try {
            connections = com.runzhi.aishell.common.config.ConfigManager.getInstance().loadConnectionConfigs();
        } catch (Exception e) {
            log.error("加载服务器列表失败", e);
            connections = List.of();
        }
        String keyword = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, List<ConnectionParams>> grouped = new LinkedHashMap<>();
        if (connections != null) {
            for (ConnectionParams conn : connections) {
                if (!keyword.isEmpty() && !matchesFilter(conn, keyword)) continue;
                String group = (conn.getGroup() == null || conn.getGroup().isBlank())
                        ? I18n.tr("main.defaultGroup") : conn.getGroup();
                grouped.computeIfAbsent(group, ignored -> new java.util.ArrayList<>()).add(conn);
            }
        }
        javafx.scene.control.TreeItem<ConnectionParams> root = new javafx.scene.control.TreeItem<>();
        for (Map.Entry<String, List<ConnectionParams>> entry : grouped.entrySet()) {
            javafx.scene.control.TreeItem<ConnectionParams> groupItem =
                    new javafx.scene.control.TreeItem<>(null,
                            new Label(entry.getKey() + " (" + entry.getValue().size() + ")"));
            groupItem.setExpanded(true);
            for (ConnectionParams conn : entry.getValue()) {
                groupItem.getChildren().add(new javafx.scene.control.TreeItem<>(conn));
            }
            root.getChildren().add(groupItem);
        }
        serverTree.setRoot(root);
    }

    private boolean matchesFilter(ConnectionParams conn, String keyword) {
        return (conn.getHost() != null && conn.getHost().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                || (conn.getProfileName() != null && conn.getProfileName().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                || (conn.getUsername() != null && conn.getUsername().toLowerCase(java.util.Locale.ROOT).contains(keyword));
    }

    /** 底栏审批定位：有待审批时显示数量与目标会话，点击切换到目标并展开 Agent。 */
    private void updateApprovalLocator() {
        if (statusApprovalLocator == null) return;
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();
        int total = tracker.totalPendingApprovals();
        if (total <= 0) {
            statusApprovalLocator.setText("");
            statusApprovalLocator.setVisible(false);
            return;
        }
        String target = tracker.sessionsWithPending().stream().sorted().findFirst().orElse(null);
        String display = target == null ? "" : " · "
                + (terminalPanelController == null ? target : terminalPanelController.tabDisplayFor(target));
        statusApprovalLocator.setText(I18n.tr("main.approvals", total, display));
        statusApprovalLocator.setVisible(true);
    }

    @FXML
    public void locateApproval() {
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();
        String target = tracker.sessionsWithPending().stream().sorted().findFirst().orElse(null);
        if (target == null) return;
        ensureTerminalPanelVisible();
        if (terminalPanelController != null) {
            terminalPanelController.focusPendingApproval(target);
        }
        if (statusLabel != null) statusLabel.setText(I18n.tr("main.approvalLocated", target));
    }

    /** 顶栏命令面板：仅点击打开，不新增任何全局快捷键。 */
    @FXML
    public void openCommandPalette() {
        if (commandPalette == null) {
            commandPalette = new com.runzhi.aishell.ui.CommandPalette(buildPaletteItems());
        }
        commandPalette.showBelow(btnCommandPalette);
    }

    private java.util.List<com.runzhi.aishell.ui.CommandPalette.Item> buildPaletteItems() {
        java.util.List<com.runzhi.aishell.ui.CommandPalette.Item> items = new java.util.ArrayList<>();
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.newConfig"), I18n.tr("main.file"), this::newConnection));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.openConfig"), I18n.tr("main.file"), this::openConfig));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.settings"), I18n.tr("main.file"), this::openSettings));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("workbench.agent"), "AI", this::openAgentWorkspace));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.terminal"), I18n.tr("main.view"), this::openTerminal));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.files"), I18n.tr("main.view"), this::openFiles));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.monitorPanel"), I18n.tr("main.view"), this::openMonitor));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.advancedMonitor"), I18n.tr("main.view"), this::openAdvancedMonitorCharts));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("workbench.aiAssistant"), "AI", this::openAIAssistant));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.history"), I18n.tr("main.view"), this::openCommandHistory));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.bookmarks"), I18n.tr("main.view"), this::openBookmarkPanel));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.reconnectAll"), I18n.tr("main.connections"), this::reconnectAllTerminals));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.disconnect"), I18n.tr("main.connections"), this::disconnectFromServer));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.switchTheme"), I18n.tr("main.view"), this::toggleTheme));
        items.add(new com.runzhi.aishell.ui.CommandPalette.Item(I18n.tr("main.systemInfo"), I18n.tr("main.help"), this::openSystemInfo));
        return items;
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
            dialog.setTitle(I18n.tr("main.terminalSettings"));
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
            stage.setTitle(I18n.tr("main.newConfigTitle"));
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
            statusLabel.setText(I18n.tr("main.noTerminal"));
            log.warn("书签命令未执行：终端未就绪");
            return;
        }
        javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText(I18n.tr("main.noTab"));
            return;
        }
        javafx.scene.control.TextField input = tabService.getTabToInput().get(selected);
        if (input != null) input.setText(command);
        commandService.executeShellCommand(selected, command);
    }

    @FXML
    public void openTerminal() {
        ensureTerminalPanelVisible();
        updateWorkModeIndicators("terminal");
        statusLabel.setText(I18n.tr("main.terminalOpened"));
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
            statusLabel.setText(I18n.tr("main.terminalUnavailable"));
        }
    }

    @FXML
    public void openMonitor() {
        // 切换到监控面板
        viewSwitcher.switchTo("monitor-panel.fxml", "white-background",
                MonitorPanelController.class, controller -> {
                    if (sshService instanceof SSHServiceImpl) {
                        controller.setSshService((SSHServiceImpl) sshService);
                    }
                    // FXML 只初始化一次：每次打开都要重新绑定当前活动会话
                    controller.bindCurrentSession();
                });
        setActiveNav(navMonitor);
        setPageContext(I18n.tr("main.monitor"), false);
    }

    @FXML
    public void openAdvancedMonitorCharts() {
        moduleManager.getNavigationManager().openInNewWindow("advanced-monitor-charts.fxml", I18n.tr("main.advancedMonitor"));
    }

    @FXML
    public void openBookmarkPanel() {
        moduleManager.getNavigationManager().openInNewWindow("bookmark-panel.fxml", I18n.tr("main.bookmarks"));
    }

    @FXML
    public void openCommandHistory() {
        final String tabId = terminalPanelController == null ? null : terminalPanelController.getSelectedTabId();
        moduleManager.getNavigationManager().openInNewWindow("command-history-dialog.fxml", I18n.tr("main.history"),
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
            dialog.setTitle(I18n.tr("main.supportTitle"));
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
            statusLabel.setText(I18n.tr("main.nothingToReconnect"));
        } else {
            statusLabel.setText(I18n.tr("main.reconnecting", count));
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

            statusLabel.setText(I18n.tr("main.connectingTo", params.getHost(), String.valueOf(params.getPort()), params.getUsername()));

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
     * 切换到终端工作区，不隐式创建连接；新建操作由显式入口负责。
     */
    private void switchToTerminalView() {
        viewSwitcher.switchTo("terminal-panel.fxml", "terminal-panel",
                TerminalPanelController.class, controller -> {
                    controller.setSshService(sshService);
                    attachTerminalPanel(controller);
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
                attachTerminalPanel(terminalController);

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
        updateWorkModeIndicators("terminal");
    }

    /**
     * 直接使用参数切换到终端视图并创建带参数的Tab
     */
    private void switchToTerminalViewWithParamsDirect(ConnectionParams params) {
        viewSwitcher.switchTo("terminal-panel.fxml", "terminal-panel",
                TerminalPanelController.class, controller -> {
                    controller.setSshService(sshService);
                    attachTerminalPanel(controller);
                    Platform.runLater(() -> {
                        if (params != null) {
                            controller.createNewTerminalTabWithParams(params);
                        } else {
                            controller.createNewTerminalTab();
                        }
                    });
                });
        updateWorkModeIndicators("terminal");
    }

    /** V1 状态栏指标同步：只表达“当前在哪一页”，连接状态与目标统一从当前标签读取 */
    private void updateWorkModeIndicators(String mode) {
        if (!"terminal".equals(mode)) return;
        setPageContext(I18n.tr("main.terminal"), true);
        setActiveNav(navTerminal);
        refreshCurrentTargetDisplay();
    }

    /** 顶栏导航任何时刻只保留一个选中项；独立窗口不改变页面归属。 */
    private void setActiveNav(javafx.scene.control.Button active) {
        for (javafx.scene.control.Button button : new javafx.scene.control.Button[]{navConnections, navTerminal, navMonitor}) {
            if (button != null) button.getStyleClass().remove("active");
        }
        if (active != null && !active.getStyleClass().contains("active")) {
            active.getStyleClass().add("active");
        }
    }

    /** 底栏左端只描述当前页面；文件抽屉与侧栏指标只在终端页有意义。 */
    private void setPageContext(String pageLabel, boolean terminalPage) {
        terminalContext = terminalPage;
        if (statusTerminalTab != null) statusTerminalTab.setText(pageLabel);
        if (btnFilesDrawer != null) {
            btnFilesDrawer.setVisible(terminalPage);
            btnFilesDrawer.setManaged(terminalPage);
        }
        if (statusPageSeparator != null) {
            statusPageSeparator.setVisible(terminalPage);
            statusPageSeparator.setManaged(terminalPage);
        }
        refreshSidebarMetricsVisibility();
    }

    /**
     * 指标组只在「终端页 + 当前活动 SSH 已连接」时显示；本地/提示页、连接管理页、
     * 监控页和断开状态都释放高度，但 activeSessionTitle/Name 身份信息始终保留。
     */
    private void refreshSidebarMetricsVisibility() {
        if (metricsGroup == null) return;
        boolean visible = terminalContext && activeSessionConnected();
        metricsGroup.setVisible(visible);
        metricsGroup.setManaged(visible);
    }

    private boolean activeSessionConnected() {
        if (sshService == null || terminalPanelController == null) return false;
        String tabId = terminalPanelController.getSelectedTabId();
        if (tabId == null) return false;
        var connection = sshService.getTabConnection(tabId);
        return connection != null && connection.isConnected();
    }

    /** 健康字段只表达当前会话的连接状态，不再承载页面名称。 */
    private void refreshConnectionHealthIndicator() {
        if (statusHealthLabel == null) return;
        String tabId = terminalPanelController == null ? null : terminalPanelController.getSelectedTabId();
        var connection = (sshService == null || tabId == null) ? null : sshService.getTabConnection(tabId);
        boolean connected = connection != null && connection.isConnected();
        statusHealthLabel.setText(I18n.tr(connected ? "main.connected" : "main.disconnected"));
        if (hostStatusDot != null) {
            hostStatusDot.setStyle(connected ? STATUS_DOT_CONNECTED_STYLE : STATUS_DOT_IDLE_STYLE);
        }
    }

    /** 会话变化后刷新连接列表、树与当前目标；两处都读 SSH 服务里的同一份记录。 */
    private void refreshSessionIndicators() {
        if (connectionListController != null) connectionListController.refreshStatuses();
        // 树的单元格在 item 不变时不会重建，必须显式刷新，否则一直显示旧状态
        if (serverTree != null) serverTree.refresh();
        refreshCurrentTargetDisplay();
    }

    /**
     * 初始化连接列表面板
     */
    private void initializeConnectionList() {
        setActiveNav(navConnections);
        setPageContext(I18n.tr("main.connections"), false);
        refreshConnectionHealthIndicator();
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
        if (connectionListController != null) connectionListController.refreshStatuses();
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
            alert.setTitle(I18n.tr("main.systemInfo"));
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
