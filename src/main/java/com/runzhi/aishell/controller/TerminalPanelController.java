/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.common.enhancement.ShellEnhancementManager;
import com.runzhi.aishell.common.enhancement.ThemeEnhancer;
import com.runzhi.aishell.common.util.TabIdentity;
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
import javafx.scene.layout.StackPane;
import javafx.geometry.Pos;
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
public class TerminalPanelController extends BaseController implements com.runzhi.aishell.common.util.Cleanable {

    /** External subscriptions must be released when this controller is destroyed. */
    private AutoCloseable agentStateSubscription;
    private javafx.collections.MapChangeListener<String, Object> connectionParamsListener;
    private javafx.beans.value.ChangeListener<javafx.scene.Scene> workspaceSceneListener;
    private final javafx.beans.value.ChangeListener<Number> workspaceWidthListener =
            (_, _, width) -> { if (!this.disposed) applyWorkspaceLayout(width.doubleValue()); };
    private volatile boolean disposed;
    /** 主界面底栏入口订阅当前标签文件区展开状态。 */
    private Runnable fileAreaStateListener;
    private Node observedFileAreaBody;
    private final javafx.beans.value.ChangeListener<Boolean> fileAreaManagedListener =
            (_, _, _) -> { if (fileAreaStateListener != null) fileAreaStateListener.run(); };

    // 标签页容器
    @FXML
    private TabPane terminalTabPane;

    // Terminal / Agent 分栏（UI 优化方案 V1 §9,§35：比例可拖动并保存）
    @FXML
    private javafx.scene.control.SplitPane terminalSplitPane;
    /** 终端宿主栈：Agent overlay 模式下浮在终端之上，不压缩终端宽度 */
    @FXML
    private StackPane terminalStack;

    /** Agent 呈现模式：宽屏三栏分栏 / 窄屏 overlay（状态页规范） */
    private enum AgentMode { SPLIT, OVERLAY, DRAWER }
    private static final double WIDE_WIDTH_THRESHOLD = 1440;
    private static final double OVERLAY_AGENT_WIDTH = 352;
    private AgentMode agentMode = AgentMode.SPLIT;
    private static final java.util.prefs.Preferences LAYOUT_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(TerminalPanelController.class);
    private static final String SPLIT_DIVIDER_KEY = "terminal.agent.split";
    private static final String AGENT_OPEN_KEY = "terminal.agent.open";
    /** 终端内 Agent 工作区宿主（默认隐藏，避免与右侧 AI 双面板） */
    @FXML
    private VBox agentWorkspaceHost;
    @FXML
    private ToggleButton btnAgentWorkspace;

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

    /** tab 属性键：连接参数 / 基础标题（不含 Agent 角标） */
    static final String CONNECTION_PARAMS_KEY = "connectionParams";
    static final String BASE_TITLE_KEY = "baseTitle";

    /** 关闭被阻止时的说明回调，默认弹出对话框；测试可替换 */
    private java.util.function.Consumer<String> blockedCloseHandler = this::showBlockedCloseDialog;

    /** 会话级桥接（initialize 之后非空），供测试直接验证会话归属 */
    private AgentWorkspaceBridge sessionBridge;

    /** 替换关闭阻止说明回调，供无头测试避免模态对话框。 */
    void setBlockedCloseHandler(java.util.function.Consumer<String> handler) {
        this.blockedCloseHandler = handler == null ? this::showBlockedCloseDialog : handler;
    }

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

    /** 按可用宽度应用 Agent 呈现模式：≥1440 三栏分栏，否则 overlay。 */
    void applyWorkspaceLayout(double width) {
        AgentMode target = width >= WIDE_WIDTH_THRESHOLD ? AgentMode.SPLIT : width >= 1200 ? AgentMode.OVERLAY : AgentMode.DRAWER;
        if (target != agentMode) setAgentMode(target);
    }

    /** 监听场景宽度切换 Agent 模式；FXML 注入的节点在 initialize 时尚未挂 Scene。 */
    private void watchWorkspaceWidth() {
        if (terminalSplitPane == null || workspaceSceneListener != null) return;
        workspaceSceneListener = (_, oldScene, newScene) -> {
            if (oldScene != null) oldScene.widthProperty().removeListener(workspaceWidthListener);
            if (newScene == null || disposed) return;
            applyWorkspaceLayout(newScene.getWidth());
            newScene.widthProperty().addListener(workspaceWidthListener);
        };
        terminalSplitPane.sceneProperty().addListener(workspaceSceneListener);
        if (terminalSplitPane.getScene() != null) workspaceSceneListener.changed(
                terminalSplitPane.sceneProperty(), null, terminalSplitPane.getScene());
    }

    private void setAgentMode(AgentMode mode) {
        agentMode = mode;
        applyAgentPlacement();
    }

    /**
     * 把 Agent 宿主放到当前模式对应的位置。收起只切可见态：
     * 不取消任务、不销毁控制器、也不释放目标标签的关闭保护。
     */
    private void applyAgentPlacement() {
        if (agentWorkspaceHost == null || terminalSplitPane == null) return;
        boolean open = agentWorkspaceHost.isVisible();
        if (terminalSplitPane.getItems().contains(agentWorkspaceHost)) {
            terminalSplitPane.getItems().remove(agentWorkspaceHost);
        }
        if (terminalStack != null && terminalStack.getChildren().contains(agentWorkspaceHost)) {
            terminalStack.getChildren().remove(agentWorkspaceHost);
        }
        if (terminalTabPane != null) {
            boolean showTerminal = !(open && agentMode == AgentMode.DRAWER);
            terminalTabPane.setVisible(showTerminal);
            terminalTabPane.setManaged(showTerminal);
            for (Tab tab : terminalTabPane.getTabs()) {
                Node body = findInTabTree(tab, node -> node.getStyleClass().contains("file-drawer-body"));
                if (body instanceof javafx.scene.layout.Region region) {
                    region.maxWidthProperty().unbind();
                    if (open && agentMode == AgentMode.OVERLAY && terminalStack != null)
                        region.maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                                () -> Math.max(200, terminalStack.getWidth() - OVERLAY_AGENT_WIDTH - 12), terminalStack.widthProperty()));
                    else region.setMaxWidth(Double.MAX_VALUE);
                }
            }
        }
        agentWorkspaceHost.getStyleClass().remove("agent-overlay");
        if (open && agentMode == AgentMode.OVERLAY) agentWorkspaceHost.getStyleClass().add("agent-overlay");
        if (!open) return;
        if (agentMode == AgentMode.SPLIT) {
            terminalSplitPane.getItems().add(agentWorkspaceHost);
            double saved = LAYOUT_PREFS.getDouble(SPLIT_DIVIDER_KEY, DEFAULT_AGENT_DIVIDER);
            if (saved < MIN_TERMINAL_RATIO || saved > MAX_TERMINAL_RATIO) saved = DEFAULT_AGENT_DIVIDER;
            // 回到分栏：恢复可伸缩约束，由分隔条决定宽度
            agentWorkspaceHost.setPrefWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
            agentWorkspaceHost.setMaxWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
            terminalSplitPane.setDividerPositions(saved);
        } else {
            // overlay：浮在终端右侧 352px（状态页规范），终端保持完整宽度。
            // StackPane 会把可伸缩子节点拉满：必须同时限制 pref 与 max，否则 Agent 覆盖整个终端。
            if (terminalStack != null) {
                terminalStack.getChildren().add(agentWorkspaceHost);
                StackPane.setAlignment(agentWorkspaceHost, Pos.CENTER_RIGHT);
            } else {
                terminalSplitPane.getItems().add(agentWorkspaceHost);
            }
            agentWorkspaceHost.setPrefWidth(agentMode == AgentMode.DRAWER ? javafx.scene.layout.Region.USE_COMPUTED_SIZE : OVERLAY_AGENT_WIDTH);
            agentWorkspaceHost.setMaxWidth(agentMode == AgentMode.DRAWER ? Double.MAX_VALUE : OVERLAY_AGENT_WIDTH);
        }
    }

    private void applyAgentWorkspaceVisibility() {
        // 无用户偏好时默认收起，让终端主工作区优先；已保存值（true/false）原样恢复。
        boolean open = LAYOUT_PREFS.getBoolean(AGENT_OPEN_KEY, false);
        if (agentWorkspaceHost != null) {
            agentWorkspaceHost.setVisible(open);
            agentWorkspaceHost.setManaged(open);
        }
        applyAgentPlacement();
        syncAgentWorkspaceToggle(open);
        if (agentWorkspaceController != null && open && terminalTabPane != null) {
            agentWorkspaceController.showTab(terminalTabPane.getSelectionModel().getSelectedItem());
        }
    }

    /** Visual and accessible toggle state follow actual workspace visibility. */
    private void syncAgentWorkspaceToggle(boolean open) {
        if (btnAgentWorkspace == null) return;
        btnAgentWorkspace.setSelected(open);
        btnAgentWorkspace.setAccessibleText(I18n.tr("workbench.agent"));
        String action = I18n.tr(open ? "workbench.agent.hide" : "workbench.agent.show");
        btnAgentWorkspace.setAccessibleHelp(action);
        if (btnAgentWorkspace.getTooltip() == null) btnAgentWorkspace.setTooltip(new Tooltip());
        btnAgentWorkspace.getTooltip().setText(action);
    }

    /** 终端内 Agent 开关：唯一主 Agent（右侧聊天默认关） */
    @FXML
    void toggleAgentWorkspace() {
        boolean open = !(agentWorkspaceHost != null && agentWorkspaceHost.isVisible());
        if (agentWorkspaceHost != null) {
            agentWorkspaceHost.setVisible(open);
            agentWorkspaceHost.setManaged(open);
        }
        applyAgentPlacement();
        LAYOUT_PREFS.putBoolean(AGENT_OPEN_KEY, open);
        syncAgentWorkspaceToggle(open);
        if (agentWorkspaceController != null && open && terminalTabPane != null) {
            agentWorkspaceController.showTab(terminalTabPane.getSelectionModel().getSelectedItem());
        }
    }

    /** 打开终端内 Agent（供 Agent 菜单与审批定位入口使用） */
    public void showAgentWorkspace() {
        if (agentWorkspaceHost != null && !agentWorkspaceHost.isVisible()) {
            toggleAgentWorkspace();
        } else if (agentWorkspaceHost != null) {
            syncAgentWorkspaceToggle(agentWorkspaceHost.isVisible());
            if (agentWorkspaceController != null && terminalTabPane != null)
                agentWorkspaceController.showTab(terminalTabPane.getSelectionModel().getSelectedItem());
        }
    }

    public void focusPendingApproval(String sessionId) {
        focusTabById(sessionId);
        showAgentWorkspace();
        if(agentWorkspaceController!=null && terminalTabPane!=null) {
            Tab target=terminalTabPane.getTabs().stream().filter(t->sessionId.equals(t.getId())).findFirst().orElse(null);
            agentWorkspaceController.focusApproval(target);
        }
    }

    /** 文件抽屉的切换结果：只有真正展开或收起才算成功。 */
    public enum DrawerResult { OPENED, CLOSED, UNAVAILABLE }

    /** 当前标签文件区状态；入口据此禁用并给出原因。 */
    public enum FileAreaState { UNAVAILABLE, CLOSED, OPEN }

    public FileAreaState fileAreaState() {
        Tab selected = terminalTabPane != null ? terminalTabPane.getSelectionModel().getSelectedItem() : null;
        if (selected == null) return FileAreaState.UNAVAILABLE;
        Node body = findFileDrawerBody(selected);
        if (body == null) return FileAreaState.UNAVAILABLE;
        return body.isVisible() && body.isManaged() ? FileAreaState.OPEN : FileAreaState.CLOSED;
    }

    /** 主入口：幂等展开当前标签文件区，已展开时保持打开而不是被再次点击收起。 */
    public DrawerResult showFileDrawer() {
        Tab selected = terminalTabPane != null ? terminalTabPane.getSelectionModel().getSelectedItem() : null;
        if (selected == null) return DrawerResult.UNAVAILABLE;
        Node body = findFileDrawerBody(selected);
        if (body == null) return DrawerResult.UNAVAILABLE;
        setFileDrawerOpen(selected, body, true);
        return DrawerResult.OPENED;
    }

    /** 底栏切换入口：展开/收起当前标签文件区，并返回真实结果。 */
    public DrawerResult toggleFileDrawer() {
        Tab selected = terminalTabPane != null ? terminalTabPane.getSelectionModel().getSelectedItem() : null;
        if (selected == null) return DrawerResult.UNAVAILABLE;
        Node body = findFileDrawerBody(selected);
        if (body == null) return DrawerResult.UNAVAILABLE;
        boolean open = !(body.isVisible() && body.isManaged());
        setFileDrawerOpen(selected, body, open);
        return open ? DrawerResult.OPENED : DrawerResult.CLOSED;
    }

    /** 当前标签是否存在可展开的文件区（入口据此置灰或提示）。 */
    public boolean hasFileDrawer() {
        return fileAreaState() != FileAreaState.UNAVAILABLE;
    }

    private Node findFileDrawerBody(Tab tab) {
        return findInTabTree(tab, n -> n.getStyleClass().contains("file-drawer-body"));
    }

    /** 直接操作抽屉体，避免用模拟点击切换状态；同时同步文件控制器的可见性。 */
    private void setFileDrawerOpen(Tab tab, Node body, boolean open) {
        body.setManaged(open);
        body.setVisible(open);
        FileManagerPanelController fileManager = findFileManagerController(tab.getContent());
        if (fileManager != null) fileManager.setPanelVisible(open);
    }

    /** 底栏入口订阅当前标签文件区的展开/收起变化，标题点击或程序切换都能同步。 */
    public void setFileAreaStateListener(Runnable listener) {
        this.fileAreaStateListener = listener;
        observeSelectedFileArea();
    }

    private void observeSelectedFileArea() {
        if (observedFileAreaBody != null) {
            observedFileAreaBody.managedProperty().removeListener(fileAreaManagedListener);
            observedFileAreaBody.visibleProperty().removeListener(fileAreaManagedListener);
            observedFileAreaBody = null;
        }
        Tab selected = terminalTabPane != null ? terminalTabPane.getSelectionModel().getSelectedItem() : null;
        if (selected == null) return;
        observedFileAreaBody = findFileDrawerBody(selected);
        if (observedFileAreaBody != null) {
            observedFileAreaBody.managedProperty().addListener(fileAreaManagedListener);
            observedFileAreaBody.visibleProperty().addListener(fileAreaManagedListener);
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

        sessionBridge = new AgentWorkspaceBridge(this::insertCommandIntoSession, this::cancelSession);
        if (agentWorkspaceController != null) {
            agentWorkspaceController.configure(
                    () -> terminalTabPane.getSelectionModel().getSelectedItem(),
                    terminalCommandService,
                    sessionBridge);
        }

        // 使用 TerminalTabService 来初始化终端面板
        if (terminalTabService != null) {
            terminalTabService.initializeTerminalPanel(terminalTabPane);
        }

        // 发布终端 TabPane：书签执行、AI 面板"发送到终端"等跨面板入口依赖此参数，
        // 此前只有消费方没有生产方，相关功能全部恒失败（2026-10-23 审计 P1-1）
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("terminalTabPane", terminalTabPane);

        // Agent 默认收起，终端优先；用户偏好与拖动比例仍原样恢复。
        applyAgentWorkspaceVisibility();

        // Agent 会话状态变化 → 更新目标标签角标（运行/待审批归属到具体 tab）
        agentStateSubscription = AgentSessionTracker.getInstance().addListener(state -> Platform.runLater(() -> {
            if (!disposed) refreshAgentBadges();
        }));

        // 响应式布局：≥1440 三栏分栏；1200–1439 Agent 为 overlay，不压缩终端
        watchWorkspaceWidth();
        if (terminalStack != null) terminalStack.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (agentMode != AgentMode.OVERLAY || agentWorkspaceHost == null || !agentWorkspaceHost.isVisible()) return;
            Node node = event.getTarget() instanceof Node target ? target : null;
            while (node != null && node != agentWorkspaceHost) node = node.getParent();
            if (node == null) toggleAgentWorkspace();
        });

        // 支持双击新建终端 Tab
        if (terminalTabService != null) {
            terminalTabService.setupDoubleClickNewTab(terminalTabPane, this::createNewTerminalTab);
        }

        // 初始化 AI 适配器
        if (terminalConnectionService != null) {
            terminalConnectionService.initializeAIAdapter();
        }

        // 分栏：Agent 展开时恢复用户拖动比例；默认收起时终端占满。
        if (terminalSplitPane != null && terminalSplitPane.getDividers() != null
                && !terminalSplitPane.getDividers().isEmpty()) {
            double saved = LAYOUT_PREFS.getDouble(SPLIT_DIVIDER_KEY, DEFAULT_AGENT_DIVIDER);
            if (saved < MIN_TERMINAL_RATIO || saved > MAX_TERMINAL_RATIO) saved = DEFAULT_AGENT_DIVIDER;
            boolean agentOpen = LAYOUT_PREFS.getBoolean(AGENT_OPEN_KEY, false);
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

        // 添加快捷键支持；Ctrl+W 与右键菜单的关闭路径统一接入审批守卫
        if (terminalUIService != null) {
            terminalUIService.setTabCloser(this::closeTab);
            terminalUIService.setupKeyboardShortcuts(terminalTabPane);
        }
        terminalTabPane.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("新建终端标签", event)) {
                createNewTab(); event.consume();
            } else if (com.runzhi.aishell.common.config.ShortcutConfigStore.matches("关闭标签", event)) {
                Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();
                if (selected != null) closeTab(selected);
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
        if (moduleManager != null && connectionParamsListener == null) {
            connectionParamsListener = change -> {
                if (disposed) return;
                if (change.getKey().equals("connection_params") && change.wasAdded()) {
                    if (!processingParams.get()) {
                        processingParams.set(true);
                        Platform.runLater(() -> {
                            PauseTransition pause = new PauseTransition(Duration.millis(100));
                            pause.setOnFinished(_ -> {
                                if (!disposed) createNewTerminalTabWithParamsFromManager();
                                processingParams.set(false);
                            });
                            pause.play();
                        });
                    }
                }
            };
            com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters().addListener(connectionParamsListener);
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
        applyAgentPlacement();
        observeSelectedFileArea();
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
     * 创建新的终端标签页；取消连接选择时保持已有标签和选中项不变。
     */
    void createNewTerminalTab() {
        ConnectionParams params = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("connection_params", ConnectionParams.class);
        if (params != null) {
            createNewTerminalTabWithParams(params);
            com.runzhi.aishell.common.util.ParameterManager.getInstance().remove("connection_params");
        } else {
            params = terminalConnectionService.showConnectionSelectionDialog();
            if (params != null) {
                Tab newTab = createConfiguredTerminalTab(params);
                String tabId = newTab.getId();

                Parent combinedView = createTerminalAndFileManagerViewForTab(tabId);
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
            }
        }
    }

    /**
     * 创建一个简单的终端标签页
     */
    public void createSimpleTerminalTab() {
        Tab newTab = createConfiguredTerminalTab(null);
        String tabId = newTab.getId();

        Parent terminalView = createSimpleTerminalViewForTab();
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
        Tab newTab = createConfiguredTerminalTab(params);
        String tabId = newTab.getId();

        Parent combinedView = createTerminalAndFileManagerViewForTab(tabId);
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

    /**
     * 把命令填入产生该提议的会话标签输入框（不执行）。
     * 目标会话不存在或已断开时停止操作并返回 false：
     * 绝不回退到当前标签（v6：旧审批/提议不得重新路由到别的会话）。
     */
    private boolean insertCommandIntoSession(String sessionId, String command) {
        Tab target = findTabById(sessionId);
        if (target == null) {
            log.warn("填入终端失败：目标会话 {} 已不存在", sessionId);
            return false;
        }
        if (!isSessionUsable(target)) {
            log.warn("填入终端失败：目标会话 {} 的连接已断开", sessionId);
            return false;
        }
        TextField input = terminalTabService == null
                ? null : terminalTabService.getTabToInput().get(target);
        if (input == null) {
            log.warn("填入终端失败：目标会话 {} 无可用输入框", sessionId);
            return false;
        }
        input.setText(command);
        input.requestFocus();
        input.positionCaret(input.getLength());
        return true;
    }

    /** 本地标签（无连接参数）可用；远程标签要求 SSH 会话仍在线。 */
    private boolean isSessionUsable(Tab tab) {
        Object stored = tab.getProperties().get(CONNECTION_PARAMS_KEY);
        if (!(stored instanceof ConnectionParams)) return true;
        if (sshService == null) sshService = moduleManager.getSshService();
        if (sshService == null) return false;
        var connection = sshService.getTabConnection(tab.getId());
        return connection != null && connection.isConnected();
    }

    /** 取消指定会话上正在运行的 Agent 任务；业务侧随后发布 CANCELLED 核销待审批。 */
    private void cancelSession(String sessionId) {
        Tab target = findTabById(sessionId);
        if (target != null && terminalCommandService != null) {
            terminalCommandService.cancelAIRequest(target);
        }
    }

    private Tab findTabById(String tabId) {
        if (tabId == null || terminalTabPane == null) return null;
        return terminalTabPane.getTabs().stream()
                .filter(tab -> tabId.equals(tab.getId()))
                .findFirst()
                .orElse(null);
    }

    /** 全部终端标签（侧栏树判断在线/Agent 归属使用）。 */
    public java.util.List<Tab> getTerminalTabs() {
        return terminalTabPane == null ? java.util.List.of()
                : java.util.List.copyOf(terminalTabPane.getTabs());
    }

    /** 目标会话的显示标题（不含 Agent 角标），供状态栏审批定位与活动会话卡使用。 */
    public String tabDisplayFor(String tabId) {
        Tab tab = findTabById(tabId);
        if (tab == null) return tabId == null ? "—" : tabId;
        Object base = tab.getProperties().get(BASE_TITLE_KEY);
        return base instanceof String baseTitle ? baseTitle : tab.getText();
    }

    /** 按 id 选中并聚焦终端标签（审批定位入口使用）。 */
    public void focusTabById(String tabId) {
        Tab tab = findTabById(tabId);
        if (tab != null) terminalTabPane.getSelectionModel().select(tab);
    }

    private static String connectionKeyOf(ConnectionParams params) {
        if (params == null || params.getHost() == null) return null;
        return (params.getUsername() == null ? "" : params.getUsername())
                + '@' + params.getHost() + ':' + params.getPort();
    }

    /** 找到与该连接对应的已开标签（user@host:port 匹配）；没有返回 null。 */
    public Tab findTabForConnection(ConnectionParams params) {
        String key = connectionKeyOf(params);
        if (key == null || terminalTabPane == null) return null;
        return terminalTabPane.getTabs().stream()
                .filter(tab -> {
                    Object stored = tab.getProperties().get(CONNECTION_PARAMS_KEY);
                    return stored instanceof ConnectionParams candidate && key.equals(connectionKeyOf(candidate));
                })
                .findFirst()
                .orElse(null);
    }

    /** 激活连接：已开标签则选中聚焦，否则新建标签并连接（供侧栏树双击/Enter 使用）。 */
    public void activateConnection(ConnectionParams params) {
        if (params == null) return;
        Tab existing = findTabForConnection(params);
        if (existing != null) {
            terminalTabPane.getSelectionModel().select(existing);
            return;
        }
        createNewTerminalTabWithParams(params);
    }

    private String nextTabId() {
        return TabIdentity.nextTabId();
    }

    private int displayNumberOf(String tabId) {
        int dash = tabId == null ? -1 : tabId.lastIndexOf('-');
        try {
            return dash < 0 ? 0 : Integer.parseInt(tabId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String connectionDisplayName(ConnectionParams params) {
        if (params == null) return "本地终端";
        String name = (params.getProfileName() != null && !params.getProfileName().isBlank())
                ? params.getProfileName() : params.getHost() + ":" + params.getPort();
        return name;
    }

    private String baseTabTitle(ConnectionParams params, int number) {
        return connectionDisplayName(params) + " · " + number;
    }

    /** 在标签上登记连接参数与基础标题，供树激活与 Agent 角标使用。 */
    private void rememberConnection(Tab tab, ConnectionParams params) {
        if (params != null) tab.getProperties().put(CONNECTION_PARAMS_KEY, params);
        tab.getProperties().put(BASE_TITLE_KEY, tab.getText());
    }

    /**
     * 统一构造终端标签：稳定身份、连接元数据与关闭保护。
     * 所有创建入口（带参连接、对话框选择、本地终端）都必须走这里，
     * 不能再各自用「标签数 + 1」拼 id，否则复用已关闭标签的序号。
     */
    Tab createConfiguredTerminalTab(ConnectionParams params) {
        String tabId = nextTabId();
        Tab newTab = new Tab(baseTabTitle(params, displayNumberOf(tabId)));
        newTab.setId(tabId);
        rememberConnection(newTab, params);
        protectTabClosure(newTab);
        return newTab;
    }

    /**
     * 挂关闭保护：皮肤层关闭按钮先过审批守卫；
     * 标签真正移除后清掉 Agent 会话状态，避免旧审批被重新路由到别的标签。
     */
    private void protectTabClosure(Tab tab) {
        if (tab == null) return;
        tab.setOnCloseRequest(event -> {
            int pending = AgentSessionTracker.getInstance().pendingApprovals(tab.getId());
            if (pending > 0) {
                event.consume();
                blockedCloseHandler.accept("该标签有 " + pending + " 项 Agent 提议待审批，"
                        + "请先在 Agent 工作区批准或拒绝，或停止该任务后再关闭。");
            }
        });
        tab.setOnClosed(event -> AgentSessionTracker.getInstance().clear(tab.getId()));
    }

    /**
     * 统一的标签关闭入口（Ctrl+W、上下文菜单等编程路径都走这里）：
     * 有待审批的 Agent 提议时阻止关闭并说明；普通标签正常关闭。
     *
     * @return true 表示标签已移除
     */
    public boolean closeTab(Tab tab) {
        if (tab == null || terminalTabPane == null) return false;
        int pending = AgentSessionTracker.getInstance().pendingApprovals(tab.getId());
        if (pending > 0) {
            blockedCloseHandler.accept("该标签有 " + pending + " 项 Agent 提议待审批，"
                    + "请先在 Agent 工作区批准或拒绝，或停止该任务后再关闭。");
            return false;
        }
        boolean removed = terminalTabPane.getTabs().remove(tab);
        if (removed) AgentSessionTracker.getInstance().clear(tab.getId());
        return removed;
    }

    /** Agent 角标刷新：运行中或待审批的会话标签追加「· Agent」。 */
    private void refreshAgentBadges() {
        if (terminalTabPane == null) return;
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();
        for (Tab tab : terminalTabPane.getTabs()) {
            Object base = tab.getProperties().get(BASE_TITLE_KEY);
            if (!(base instanceof String baseTitle)) continue;
            boolean active = tracker.isRunning(tab.getId())
                    || tracker.pendingApprovals(tab.getId()) > 0;
            tab.setText(active ? baseTitle + " · Agent" : baseTitle);
        }
    }

    private void showBlockedCloseDialog(String reason) {
        Alert alert = new Alert(Alert.AlertType.WARNING, reason, ButtonType.OK);
        alert.setTitle("无法关闭标签");
        alert.setHeaderText("Agent 审批进行中");
        alert.showAndWait();
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

    /**
     * 当前选中标签绑定的 SSH 连接参数；本地/提示标签返回 null。
     * 连接尚未注册到 SSHService 时也能从标签元数据取出目标，避免顶栏目标空白或残留上一台主机。
     */
    public ConnectionParams getSelectedConnectionParams() {
        if (terminalTabPane == null) return null;
        Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();
        if (selected == null) return null;
        Object stored = selected.getProperties().get(CONNECTION_PARAMS_KEY);
        return stored instanceof ConnectionParams params ? params : null;
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
    /** Dispose external listeners without rerouting or approving any task. */
    @Override public void onWindowClosing() {
        if (disposed) return;
        disposed = true;
        fileAreaStateListener = null;
        if (observedFileAreaBody != null) {
            observedFileAreaBody.managedProperty().removeListener(fileAreaManagedListener);
            observedFileAreaBody.visibleProperty().removeListener(fileAreaManagedListener);
            observedFileAreaBody = null;
        }
        if (agentStateSubscription != null) {
            try { agentStateSubscription.close(); }
            catch (Exception failure) { log.warn("Unable to release Agent state listener", failure); }
            agentStateSubscription = null;
        }
        if (connectionParamsListener != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters().removeListener(connectionParamsListener);
            connectionParamsListener = null;
        }
        if (terminalSplitPane != null && workspaceSceneListener != null) {
            if (terminalSplitPane.getScene() != null) terminalSplitPane.getScene().widthProperty().removeListener(workspaceWidthListener);
            terminalSplitPane.sceneProperty().removeListener(workspaceSceneListener);
            workspaceSceneListener = null;
        }
        if (agentWorkspaceController != null) agentWorkspaceController.onWindowClosing();
    }

}
