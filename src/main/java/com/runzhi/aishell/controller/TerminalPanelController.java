/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.enhancement.ShellEnhancementManager;
import com.runzhi.aishell.common.enhancement.ThemeEnhancer;
import com.runzhi.aishell.common.util.UITools;
import com.runzhi.aishell.service.TerminalCommandService;
import com.runzhi.aishell.service.TerminalConnectionService;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.service.TerminalUIService;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.terminal.InteractiveTerminalView;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端面板控制器 - 简化版本
 * <p>
 * 职责：
 * - 管理标签页容器（TabPane）
 * - 协调终端服务的调用
 * - 处理用户交互事件
 * <p>
 * 每个标签页的内容由 TerminalUIService 动态创建，
 * 包含 terminal-tab.fxml（终端）和 file-manager-panel.fxml（文件管理器）
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class TerminalPanelController extends BaseController {

    // 标签页容器
    @FXML
    private TabPane terminalTabPane;

    // Terminal / Agent 分栏（UI 优化方案 V1 §9,§35：比例可拖动并保存）
    @FXML
    private javafx.scene.control.SplitPane terminalSplitPane;
    private static final java.util.prefs.Preferences LAYOUT_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(TerminalPanelController.class);
    private static final String SPLIT_DIVIDER_KEY = "terminal.agent.split";
    private static final String AGENT_OPEN_KEY = "terminal.agent.open";
    /** 终端内 Agent 工作区宿主（默认隐藏，避免与右侧 AI 双面板） */
    @FXML
    private VBox agentWorkspaceHost;
    @FXML
    private Button btnAgentWorkspace;

    // 服务引用
    private SSHService sshService;
    private TerminalTabService terminalTabService;
    private TerminalCommandService terminalCommandService;
    @FXML
    private AgentWorkspaceController agentWorkspaceController;
    private TerminalConnectionService terminalConnectionService;
    private TerminalUIService terminalUIService;

    private com.runzhi.aishell.common.ai.AIHelperService aiHelperService;
    private final Map<String, List<ForwardingRule>> forwardingRules = new HashMap<>();

    private record ForwardingRule(String type, String bindHost, int listenPort,
                                  String targetHost, int targetPort) {
        @Override public String toString() {
            return type + "  " + bindHost + ":" + listenPort
                    + ("动态 SOCKS".equals(type) ? "" : "  →  " + targetHost + ":" + targetPort);
        }
    }

    // 工具栏相关方法 (替代 TerminalToolbarController)
    
    @FXML
    void createNewTab() {
        createNewTerminalTab();
    }

    /** 默认给 Agent 保留约四分之一窗口宽度，同时让 Terminal 继续占主导。 */
    private static final double DEFAULT_AGENT_DIVIDER = 0.76;
    private static final double MIN_TERMINAL_RATIO = 0.45;
    private static final double MAX_TERMINAL_RATIO = 0.82;

    private void applyAgentWorkspaceVisibility() {
        boolean open = LAYOUT_PREFS.getBoolean(AGENT_OPEN_KEY, true);
        double saved = LAYOUT_PREFS.getDouble(SPLIT_DIVIDER_KEY, DEFAULT_AGENT_DIVIDER);
        if (saved < MIN_TERMINAL_RATIO || saved > MAX_TERMINAL_RATIO) saved = DEFAULT_AGENT_DIVIDER;

        if (agentWorkspaceHost != null) {
            agentWorkspaceHost.setVisible(open);
            agentWorkspaceHost.setManaged(open);
            if (open) {
                agentWorkspaceHost.setPrefWidth(Math.max(280, (1.0 - saved) * 900));
            }
        }
        if (terminalSplitPane != null) {
            if (open) {
                terminalSplitPane.setDividerPositions(saved);
            } else {
                // 收起时终端占满
                terminalSplitPane.setDividerPositions(1.0);
            }
        }
        if (btnAgentWorkspace != null) {
            btnAgentWorkspace.setText(open ? "Agent ●" : "Agent");
        }
        if (agentWorkspaceController != null && open && terminalTabPane != null) {
            agentWorkspaceController.showTab(terminalTabPane.getSelectionModel().getSelectedItem());
        }
    }

    /** 终端内 Agent 开关：唯一主 Agent（右侧聊天默认关） */
    @FXML
    void toggleAgentWorkspace() {
        boolean open = !(agentWorkspaceHost != null && agentWorkspaceHost.isVisible());
        if (agentWorkspaceHost != null) {
            agentWorkspaceHost.setVisible(open);
            agentWorkspaceHost.setManaged(open);
        }
        if (open && terminalSplitPane != null) {
            double saved = LAYOUT_PREFS.getDouble(SPLIT_DIVIDER_KEY, DEFAULT_AGENT_DIVIDER);
            if (saved < MIN_TERMINAL_RATIO || saved > MAX_TERMINAL_RATIO) saved = DEFAULT_AGENT_DIVIDER;
            terminalSplitPane.setDividerPositions(saved);
        } else if (terminalSplitPane != null) {
            terminalSplitPane.setDividerPositions(1.0);
        }
        if (agentWorkspaceController != null && open && terminalTabPane != null) {
            agentWorkspaceController.showTab(terminalTabPane.getSelectionModel().getSelectedItem());
        }
        LAYOUT_PREFS.putBoolean(AGENT_OPEN_KEY, open);
        if (btnAgentWorkspace != null) {
            btnAgentWorkspace.setText(open ? "Agent ●" : "Agent");
        }
    }

    /** 打开终端内 Agent（供主界面「AI」入口使用） */
    public void showAgentWorkspace() {
        if (agentWorkspaceHost != null && !agentWorkspaceHost.isVisible()) {
            toggleAgentWorkspace();
        } else if (agentWorkspaceController != null && terminalTabPane != null) {
            agentWorkspaceController.showTab(terminalTabPane.getSelectionModel().getSelectedItem());
        }
    }

    /** V1 §12：切换当前标签的 SFTP 文件 Drawer */
    public void toggleFileDrawer() {
        Tab selected = terminalTabPane != null ? terminalTabPane.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            return;
        }
        Node header = findInTabTree(selected, n -> n.getStyleClass().contains("file-drawer-header"));
        if (header != null) {
            javafx.scene.input.MouseEvent click = new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false,
                    true, false, true, null);
            javafx.event.Event.fireEvent(header, click);
            return;
        }
        Node body = findInTabTree(selected, n -> n.getStyleClass().contains("file-drawer-body"));
        if (body instanceof VBox vb) {
            boolean open = !vb.isManaged();
            vb.setManaged(open);
            vb.setVisible(open);
        }
    }

    private Node findInTabTree(Tab tab, java.util.function.Predicate<Node> pred) {
        if (tab == null || tab.getContent() == null) return null;
        return findInTree(tab.getContent(), pred);
    }

    private Node findInTree(Node node, java.util.function.Predicate<Node> pred) {
        if (node == null) return null;
        if (pred.test(node)) return node;
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node hit = findInTree(child, pred);
                if (hit != null) return hit;
            }
        }
        return null;
    }
    
    @FXML
    void attemptReconnection() {
        attemptReconnectionForSelectedTab();
    }
    
    @FXML
    void clearScreen() {
        if (terminalTabPane != null) {
            Tab selectedTab = terminalTabPane.getSelectionModel().getSelectedItem();
            if (selectedTab != null) {
                if (InteractiveTerminalView.clear(selectedTab)) {
                    return;
                }
                InlineCssTextArea outputArea = findOutputAreaInTab(selectedTab);
                if (outputArea != null) {
                    outputArea.clear();
                    outputArea.appendText("屏幕已清空\n");
                }
            }
        }
    }
    
    @FXML
    void copySelection() {
        if (terminalTabPane != null) {
            Tab selectedTab = terminalTabPane.getSelectionModel().getSelectedItem();
            if (selectedTab != null) {
                if (InteractiveTerminalView.copySelection(selectedTab)) {
                    return;
                }
                InlineCssTextArea outputArea = findOutputAreaInTab(selectedTab);
                if (outputArea != null && outputArea.getSelectedText() != null && !outputArea.getSelectedText().isEmpty()) {
                    outputArea.copy();
                }
            }
        }
    }
    
    @FXML
    void pasteContent() {
        if (terminalTabPane != null) {
            Tab selectedTab = terminalTabPane.getSelectionModel().getSelectedItem();
            if (selectedTab != null) {
                if (InteractiveTerminalView.paste(selectedTab)) {
                    return;
                }
                InlineCssTextArea outputArea = findOutputAreaInTab(selectedTab);
                if (outputArea != null) {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    if (clipboard.hasString()) {
                        String content = clipboard.getString();
                        if (content != null) {
                            outputArea.appendText(content);
                        }
                    }
                }
            }
        }
    }
    
    @FXML
    void changeTheme() {
        ShellEnhancementManager.getInstance();
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("主题选择");
        dialog.setHeaderText("选择终端主题");
        
        javafx.scene.control.ButtonType applyButtonType = new javafx.scene.control.ButtonType("应用", 
            javafx.scene.control.ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, javafx.scene.control.ButtonType.CANCEL);
        
        // 获取当前选中的 Tab 和输出区域
        Tab selectedTab = terminalTabPane.getSelectionModel().getSelectedItem();
        InlineCssTextArea outputArea = selectedTab != null ? findOutputAreaInTab(selectedTab) : null;
        String tabId = selectedTab != null ? selectedTab.getId() : null;
        
        // 获取主题模块实例
        com.runzhi.aishell.terminal.enhancement.modules.EnhancedThemeModule themeModule = 
            com.runzhi.aishell.terminal.enhancement.TerminalEnhancementManager.getInstance()
                .getEnhancedThemeModule();
        
        javafx.scene.layout.VBox themeSelector = ThemeEnhancer
            .createThemeSelector(themeModule, outputArea, tabId, selectedTab);
        dialog.getDialogPane().setContent(themeSelector);
        dialog.showAndWait();
    }
    
    @FXML
    void showSettings() {
        SettingsDialogController.showDialog(
                terminalTabPane != null && terminalTabPane.getScene() != null
                        ? terminalTabPane.getScene().getWindow() : null);
    }

    @FXML
    void showPortForwarding() {
        Tab tab = terminalTabPane == null ? null : terminalTabPane.getSelectionModel().getSelectedItem();
        if (tab == null || tab.getId() == null || tab.getId().isBlank()) {
            showMessage(Alert.AlertType.WARNING, "SSH 隧道", "请先选择已连接的终端标签页。");
            return;
        }
        if (sshService == null) sshService = moduleManager.getSshService();
        String tabId = tab.getId();
        List<ForwardingRule> rules = forwardingRules.computeIfAbsent(tabId, ignored -> new ArrayList<>());

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("SSH 隧道");
        dialog.setHeaderText("当前终端的端口转发（关闭终端时自动失效）");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ComboBox<String> type = new ComboBox<>();
        type.getItems().addAll("本地转发", "远程转发", "动态 SOCKS");
        type.getSelectionModel().selectFirst();
        TextField bindHost = new TextField("127.0.0.1");
        Spinner<Integer> listenPort = new Spinner<>(0, 65535, 0);
        listenPort.setEditable(true);
        TextField targetHost = new TextField(com.runzhi.aishell.common.config.AppConstants.DEFAULT_SSH_HOST);
        Spinner<Integer> targetPort = new Spinner<>(1, 65535, 80);
        targetPort.setEditable(true);
        Button add = new Button("添加隧道");
        ListView<ForwardingRule> list = new ListView<>();
        list.getItems().setAll(rules);
        list.setPrefHeight(180);
        Button remove = new Button("关闭选中隧道");

        type.valueProperty().addListener((o, old, value) -> {
            boolean remote = "远程转发".equals(value);
            bindHost.setText(remote ? "0.0.0.0" : "127.0.0.1");
            listenPort.getValueFactory().setValue(remote ? 8080 : 0);
            boolean dynamic = "动态 SOCKS".equals(value);
            targetHost.setDisable(dynamic);
            targetPort.setDisable(dynamic);
        });
        add.setOnAction(event -> {
            try {
                String forwardingType = type.getValue();
                boolean local = "本地转发".equals(forwardingType);
                boolean dynamic = "动态 SOCKS".equals(forwardingType);
                int requested = listenPort.getValue();
                int actual;
                if (dynamic) {
                    actual = sshService.openDynamicPortForward(tabId, bindHost.getText().trim(), requested);
                } else if (local) {
                    actual = sshService.openLocalPortForward(tabId, bindHost.getText().trim(), requested,
                            targetHost.getText().trim(), targetPort.getValue());
                } else {
                    if (requested == 0) throw new IllegalArgumentException("远程转发端口不能为 0");
                    sshService.openRemotePortForward(tabId, requested, targetHost.getText().trim(), targetPort.getValue());
                    actual = requested;
                }
                ForwardingRule rule = new ForwardingRule(forwardingType, bindHost.getText().trim(), actual,
                        targetHost.getText().trim(), targetPort.getValue());
                rules.add(rule);
                list.getItems().add(rule);
                listenPort.getValueFactory().setValue(actual);
            } catch (Exception e) {
                showMessage(Alert.AlertType.ERROR, "创建隧道失败", e.getMessage());
            }
        });
        remove.setOnAction(event -> {
            ForwardingRule rule = list.getSelectionModel().getSelectedItem();
            if (rule == null) return;
            try {
                if ("动态 SOCKS".equals(rule.type())) sshService.closeDynamicPortForward(tabId, rule.bindHost(), rule.listenPort());
                else if ("本地转发".equals(rule.type())) sshService.closeLocalPortForward(tabId, rule.bindHost(), rule.listenPort());
                else sshService.closeRemotePortForward(tabId, rule.listenPort());
                rules.remove(rule);
                list.getItems().remove(rule);
            } catch (Exception e) {
                showMessage(Alert.AlertType.ERROR, "关闭隧道失败", e.getMessage());
            }
        });

        VBox form = new VBox(10,
                new HBox(8, new Label("类型"), type),
                new HBox(8, new Label("监听地址"), bindHost, new Label("端口"), listenPort),
                new HBox(8, new Label("目标地址"), targetHost, new Label("端口"), targetPort),
                add, new Label("已开启"), list, remove);
        dialog.getDialogPane().setContent(form);
        dialog.showAndWait();
    }

    private void showMessage(Alert.AlertType type, String title, String text) {
        Alert alert = new Alert(type, text == null ? "未知错误" : text, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
    
    private InlineCssTextArea findOutputAreaInTab(Tab tab) {
        return tab == null ? null : findOutputArea(tab.getContent());
    }

    private InlineCssTextArea findOutputArea(javafx.scene.Node node) {
        if (node instanceof InlineCssTextArea area
                && ("terminalOutput".equals(area.getId()) || area.getId() == null)) {
            return area;
        }
        if (node instanceof Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                InlineCssTextArea found = findOutputArea(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // 监控面板控制器引用
    private MonitorPanelController monitorPanelController;

    // 用于防止重复处理连接参数
    private final java.util.concurrent.atomic.AtomicBoolean processingParams = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化模块管理器
        initializeModuleManager();

        // 从模块管理器获取终端服务
        terminalTabService = moduleManager.getTerminalTabService();
        terminalCommandService = moduleManager.getTerminalCommandService();
        terminalConnectionService = moduleManager.getTerminalConnectionService();
        terminalUIService = moduleManager.getTerminalUIService();

        if (agentWorkspaceController != null) {
            agentWorkspaceController.configure(
                    () -> terminalTabPane.getSelectionModel().getSelectedItem(),
                    terminalCommandService,
                    this::insertCommandIntoTerminal);
        }

        // 使用 TerminalTabService 来初始化终端面板
        if (terminalTabService != null) {
            terminalTabService.initializeTerminalPanel(terminalTabPane);
        }

        // 发布终端 TabPane：书签执行、AI 面板"发送到终端"等跨面板入口依赖此参数，
        // 此前只有消费方没有生产方，相关功能全部恒失败（2026-10-23 审计 P1-1）
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("terminalTabPane", terminalTabPane);

        // Agent 与 Terminal 并列显示，初始比例为 76:24；用户调整后恢复保存值。
        applyAgentWorkspaceVisibility();

        // 支持双击新建终端 Tab
        if (terminalTabService != null) {
            terminalTabService.setupDoubleClickNewTab(terminalTabPane, this::createNewTerminalTab);
        }

        // 初始化 AI 适配器
        if (terminalConnectionService != null) {
            terminalConnectionService.initializeAIAdapter();
        }

        // 分栏：Terminal 默认占 76%，Agent 可拖动并记忆。
        if (terminalSplitPane != null && terminalSplitPane.getDividers() != null
                && !terminalSplitPane.getDividers().isEmpty()) {
            double saved = LAYOUT_PREFS.getDouble(SPLIT_DIVIDER_KEY, DEFAULT_AGENT_DIVIDER);
            if (saved < MIN_TERMINAL_RATIO || saved > MAX_TERMINAL_RATIO) saved = DEFAULT_AGENT_DIVIDER;
            boolean agentOpen = LAYOUT_PREFS.getBoolean(AGENT_OPEN_KEY, true);
            terminalSplitPane.setDividerPositions(agentOpen ? saved : 1.0);
            terminalSplitPane.getDividers().get(0).positionProperty().addListener(
                    (_, _, newVal) -> {
                        double v = newVal.doubleValue();
                        if (v >= MIN_TERMINAL_RATIO && v <= MAX_TERMINAL_RATIO) {
                            LAYOUT_PREFS.putDouble(SPLIT_DIVIDER_KEY, v);
                        }
                    });
        }

        // 初始化AI助手服务
        aiHelperService = new com.runzhi.aishell.common.ai.AIHelperService();

        // 添加快捷键支持
        if (terminalUIService != null) {
            terminalUIService.setupKeyboardShortcuts(terminalTabPane);
        }
        terminalTabPane.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("新建终端标签", event)) {
                createNewTab(); event.consume();
            } else if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("关闭标签", event)) {
                Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();
                if (selected != null) terminalTabPane.getTabs().remove(selected);
                event.consume();
            } else if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("重新连接", event)) {
                attemptReconnection(); event.consume();
            } else if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("清屏", event)) {
                clearScreen(); event.consume();
            } else if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("打开设置", event)) {
                showSettings(); event.consume();
            }
        });
    
        // 监听模块管理器中的连接参数变化
        setupConnectionParamsListener();
    
        // 监听 Tab 选择变化
        setupTabSwitchListener();
    }

    /**
     * 设置连接参数监听器
     */
    private void setupConnectionParamsListener() {
        if (moduleManager != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters().addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                if (change.getKey().equals("connection_params") && change.wasAdded()) {
                    if (!processingParams.get()) {
                        processingParams.set(true);
                        Platform.runLater(() -> {
                            PauseTransition pause = new PauseTransition(Duration.millis(100));
                            pause.setOnFinished(_ -> {
                                createNewTerminalTabWithParamsFromManager();
                                processingParams.set(false);
                            });
                            pause.play();
                        });
                    }
                }
            });
        }
    }

    /**
     * 设置标签页切换监听器
     */
    private void setupTabSwitchListener() {
        terminalTabPane.getSelectionModel().selectedItemProperty().addListener((_, _, newValue) -> Platform.runLater(() -> handleTabSwitch(newValue)));
    }

    /**
     * 设置监控面板控制器
     */
    public void setMonitorPanelController(MonitorPanelController monitorPanelController) {
        this.monitorPanelController = monitorPanelController;
        if (terminalUIService != null) {
            terminalUIService.setMonitorPanelController(monitorPanelController);
        }
    }

    /**
     * 处理 Tab 切换事件
     */
    private void handleTabSwitch(Tab newTab) {
        if (agentWorkspaceController != null) agentWorkspaceController.showTab(newTab);
        if (newTab != null && newTab.getId() != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().put("current_active_tab", newTab.getId(), 5000);
            // 仅在文件抽屉可见时刷新，避免切换标签时列表反复刷新
            refreshFileManagerForTab(newTab);
        }
    }

    private void refreshFileManagerForTab(Tab tab) {
        if (tab == null || tab.getId() == null) return;
        Object content = tab.getContent();
        var fm = findFileManagerController(content);
        if (fm != null) {
            if (fm.isPanelVisible()) {
                fm.setPanelVisible(true); // 可见时会触发受控刷新
            }
            return;
        }
        // 兜底：走模块管理器
        moduleManager.refreshFileListForTab(tab.getId());
        log.debug("File list refresh triggered for tab: {}", tab.getText());
    }

    private com.runzhi.aishell.controller.FileManagerPanelController findFileManagerController(Object node) {
        if (node == null) return null;
        if (node instanceof javafx.scene.Node n) {
            Object data = n.getUserData();
            if (data instanceof com.runzhi.aishell.controller.FileManagerPanelController fm) return fm;
            if (n instanceof Parent parent) {
                for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                    var hit = findFileManagerController(child);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
    }

    /**
     * 创建新的终端标签页
     */
    void createNewTerminalTab() {
        ConnectionParams params = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("connection_params", ConnectionParams.class);
        if (params != null) {
            createNewTerminalTabWithParams(params);
            com.runzhi.aishell.common.util.ParameterManager.getInstance().remove("connection_params");
        } else {
            params = terminalConnectionService.showConnectionSelectionDialog();
            if (params != null) {
                int tabCount = terminalTabPane.getTabs().size() + 1;
                String tabId = "tab" + tabCount;

                Parent combinedView = createTerminalAndFileManagerViewForTab(tabId);

                Tab newTab = new Tab(params.getHost() + ":" + params.getPort() + " - Terminal " + tabCount);
                newTab.setId(tabId);
                newTab.setContent(combinedView);

                terminalTabPane.getTabs().add(newTab);
                terminalTabPane.getSelectionModel().select(newTab);

                ConnectionParams finalParams = params;
                Platform.runLater(() -> {
                    PauseTransition pause = new PauseTransition(Duration.millis(200));
                    pause.setOnFinished(_ -> {
                        setupTabTerminal(newTab, tabId);
                        PauseTransition connectDelay = new PauseTransition(Duration.millis(150));
                        connectDelay.setOnFinished(_ -> establishSSHConnectionForTab(tabId, finalParams, newTab));
                        connectDelay.play();
                    });
                    pause.play();
                });
            } else {
                createSimpleTerminalTab();
            }
        }
    }

    /**
     * 创建一个简单的终端标签页
     */
    public void createSimpleTerminalTab() {
        int tabCount = terminalTabPane.getTabs().size() + 1;
        String tabId = "tab" + tabCount;

        Parent terminalView = createSimpleTerminalViewForTab();

        Tab newTab = new Tab("Terminal " + tabCount);
        newTab.setId(tabId);
        newTab.setContent(terminalView);

        terminalTabPane.getTabs().add(newTab);
        terminalTabPane.getSelectionModel().select(newTab);

        Platform.runLater(() -> {
            setupTabTerminal(newTab, tabId);
            InlineCssTextArea outputArea = terminalTabService.getTabToOutput().get(newTab);
            if (outputArea != null) {
                Platform.runLater(() -> {
                    outputArea.appendText("本地终端已启动\n");
                    outputArea.appendText("当前用户: " + System.getProperty("user.name") + "\n");
                    outputArea.appendText("当前路径: " + System.getProperty("user.dir") + "\n");
                    outputArea.moveTo(outputArea.getLength());
                    outputArea.requestFollowCaret();
                });
            }
        });
    }

    /**
     * 创建简单的终端视图
     */
    public Parent createSimpleTerminalViewForTab() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
            loader.setResources(I18n.bundle());
            VBox tabContent = loader.load();
            UITools.setDarkTheme(tabContent);
            com.runzhi.aishell.controller.TerminalTabController terminalTabController = loader.getController();
            terminalTabController.initializeDarkTheme();
            tabContent.getProperties().put("terminalTabController", terminalTabController);
            return tabContent;
        } catch (Exception e) {
            log.error("创建简单终端视图失败: {}", e.getMessage(), e);
            return new VBox();
        }
    }

    /**
     * 使用指定的连接参数创建新的终端Tab
     */
    public void createNewTerminalTabWithParams(ConnectionParams params) {
        int tabCount = terminalTabPane.getTabs().size() + 1;
        String tabId = "tab" + tabCount;

        Parent combinedView = createTerminalAndFileManagerViewForTab(tabId);

        Tab newTab = new Tab(params.getHost() + ":" + params.getPort() + " - Terminal " + tabCount);
        newTab.setId(tabId);
        newTab.setContent(combinedView);

        terminalTabPane.getTabs().add(newTab);
        terminalTabPane.getSelectionModel().select(newTab);

        Platform.runLater(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(100));
            pause.setOnFinished(event -> {
                setupTabTerminal(newTab, tabId);
                PauseTransition connectDelay = new PauseTransition(Duration.millis(50));
                connectDelay.setOnFinished(connectEvent -> {
                    establishSSHConnectionForTab(tabId, params, newTab);
                });
                connectDelay.play();
            });
            pause.play();
        });
    }

    /**
     * 从参数管理器获取连接参数并创建新的终端Tab
     */
    public void createNewTerminalTabWithParamsFromManager() {
        ConnectionParams params = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("connection_params", ConnectionParams.class);
        if (params != null) {
            Platform.runLater(() -> {
                createNewTerminalTabWithParams(params);
                com.runzhi.aishell.common.util.ParameterManager.getInstance().remove("connection_params");
            });
        } else {
            log.warn("从参数管理器获取连接参数失败，无法创建终端标签页");
        }
    }

    /**
     * 为每个Tab设置独立终端
     */
    private void setupTabTerminal(Tab tab, String tabId) {
        if (terminalTabService != null) {
            terminalTabService.setupTabTerminal(tab, tabId);
        }
        // 接线终端标签控制器：重连按钮、连接信息栏与输出区交互此前因 setTerminalPanelController
        // 无调用方面全部失效（2026-10-23 审计 P1-2）
        if (sshService == null && moduleManager != null) sshService = moduleManager.getSshService();
        Object controller = findTerminalTabController(tab.getContent());
        if (controller instanceof com.runzhi.aishell.controller.TerminalTabController tabController
                && sshService != null && tabId != null) {
            tabController.initializeDarkTheme();
            tabController.setTerminalPanelController(this, tab, tabId, sshService);
            tab.getProperties().put("terminalTabController", tabController);
        }
    }

    /** 从视图树中提取 TerminalUIServiceImpl 注册的 TerminalTabController。 */
    private Object findTerminalTabController(javafx.scene.Node node) {
        if (node == null) return null;
        Object data = node.getProperties().get("terminalTabController");
        if (data != null) return data;
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                Object hit = findTerminalTabController(child);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * 为标签页建立SSH连接
     */
    private void establishSSHConnectionForTab(String tabId, ConnectionParams params, Tab tab) {
        if (terminalConnectionService != null) {
            terminalConnectionService.establishSSHConnectionForTab(tabId, params, tab);
        }
    }

    /**
     * 在指定Tab中执行命令
     */
    void executeCurrentCommandInTab(Tab tab, TextField input, ChoiceBox<String> modeComboBox) {
        if (terminalCommandService != null) {
            terminalCommandService.executeCurrentCommandInTab(tab, input, modeComboBox);
        }
    }

    /** Places an approved Agent proposal into the active tab input without executing it. */
    private void insertCommandIntoTerminal(String command) {
        Tab selected = terminalTabPane == null ? null : terminalTabPane.getSelectionModel().getSelectedItem();
        TextField input = selected == null || terminalTabService == null
                ? null : terminalTabService.getTabToInput().get(selected);
        if (input == null) return;
        input.setText(command);
        input.requestFocus();
        input.positionCaret(input.getLength());
    }

    /**
     * 为指定的 Tab 创建终端和文件管理器的组合界面
     */
    public Parent createTerminalAndFileManagerViewForTab(String tabId) {
        if (terminalUIService != null) {
            Parent view = terminalUIService.createTerminalAndFileManagerViewForTab(tabId);
            if (terminalUIService instanceof com.runzhi.aishell.service.impl.TerminalUIServiceImpl) {
                FileManagerPanelController fileManagerController =
                        ((com.runzhi.aishell.service.impl.TerminalUIServiceImpl) terminalUIService).extractFileManagerController(view);
                if (fileManagerController != null) {
                    moduleManager.registerFileManagerController(tabId, fileManagerController);
                    log.info("Registered file manager controller for tab: {}", tabId);
                }
            }
            return view;
        }
        return null;
    }

    /**
     * 从标签页获取Tab ID
     */
    String getTabId(Tab tab) {
        if (terminalTabService != null) {
            return terminalTabService.getTabId(tab);
        }
        return null;
    }

    /**
     * 获取当前选中标签页的 Tab ID，供外部（如命令历史对话框）使用。
     *
     * @return 当前标签页 ID；没有标签页时返回 null
     */
    public String getSelectedTabId() {
        if (terminalTabPane == null) return null;
        Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();
        return selected == null ? null : getTabId(selected);
    }

    @FXML
    void executeCurrentCommand() {
        Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();

        if (selected != null) {
            TextField input = terminalTabService.getTabToInput().get(selected);
            if (input != null) {
                String command = input.getText();
                if (!command.trim().isEmpty()) {
                    if (command.toLowerCase().startsWith("ai:")) {
                        String aiCommand = command.substring(3).trim();
                        InlineCssTextArea output = terminalTabService.getTabToOutput().get(selected);
                        if (output != null && terminalCommandService != null) {
                            terminalCommandService.executeAICommandDirectly(selected, aiCommand);
                        }
                    } else {
                        if (terminalCommandService != null) {
                            terminalCommandService.addCommandToHistory(selected, command);
                            terminalCommandService.executeShellCommand(selected, command);
                        }
                    }
                    input.clear();
                }
            }
        }
    }

    /**
     * 处理断开连接
     */
    public void handleDisconnection() {
        if (terminalConnectionService != null) {
            terminalConnectionService.handleDisconnection();
        }
    }

    /**
     * 尝试重新连接到选中的标签页
     */
    public void attemptReconnectionForSelectedTab() {
        if (terminalTabPane != null) {
            Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();
            if (selected != null && terminalConnectionService != null) {
                terminalConnectionService.attemptReconnection(selected);
            }
        }
    }

    /** 对所有保存了 SSH 连接参数的终端标签发起重连，本地终端会自动跳过。 */
    public int attemptReconnectionForAllSshTabs() {
        if (terminalTabPane == null || terminalConnectionService == null
                || terminalConnectionService.getSshService() == null) {
            return 0;
        }
        int reconnectCount = 0;
        for (Tab tab : terminalTabPane.getTabs()) {
            String tabId = getTabId(tab);
            if (tabId != null
                    && terminalConnectionService.getSshService().getTabConnectionParams(tabId) != null) {
                terminalConnectionService.attemptReconnection(tab);
                reconnectCount++;
            }
        }
        return reconnectCount;
    }

    /**
     * 打开终端设置对话框
     */
    public void openTerminalSettings() {
        try {
            ShellEnhancementManager enhancementManager = ShellEnhancementManager.getInstance();
            
            // 获取当前选中的 Tab
            Tab selectedTab = terminalTabPane.getSelectionModel().getSelectedItem();
            InlineCssTextArea outputArea = selectedTab != null ? findOutputAreaInTab(selectedTab) : null;
            String tabId = selectedTab != null ? selectedTab.getId() : null;
            
            // 获取主题模块实例
            com.runzhi.aishell.terminal.enhancement.modules.EnhancedThemeModule themeModule = 
                com.runzhi.aishell.terminal.enhancement.TerminalEnhancementManager.getInstance()
                    .getEnhancedThemeModule();
            
            VBox themeSelector = ThemeEnhancer
                .createThemeSelector(themeModule, outputArea, tabId);

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
        }
    }

    @FXML
    public void openNewConnection() {
        createNewTerminalTab();
    }

    @FXML
    public void refreshFileList() {
        log.info("刷新文件列表");
        if (terminalUIService != null) {
            terminalUIService.refreshFileList();
        }
    }

    @FXML
    public void executeCommand() {
        Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();

        if (selected != null) {
            TextField commandInputField = terminalTabService.getTabToInput().get(selected);
            if (commandInputField != null) {
                String command = commandInputField.getText();
                if (!command.trim().isEmpty() && terminalCommandService != null) {
                    terminalCommandService.addCommandToHistory(selected, command);
                    terminalCommandService.executeShellCommand(selected, command);
                    commandInputField.clear();
                }
            }
        }
    }

    /**
     * 显示连接列表
     */
    private void showConnectionList() {
        if (moduleManager != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().put("show_connection_list", true, 5000);
        }
    }
}
