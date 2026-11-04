/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.ai.AIService;
import com.runzhi.aishell.agent.CommandRiskAssessor;
import com.runzhi.aishell.common.util.ClipboardUtils;
import com.runzhi.aishell.common.util.DialogUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class AIAssistantPanelController extends BaseController
        implements com.runzhi.aishell.common.util.Cleanable {

    @FXML
    private TextFlow terminalOutput;

    @FXML
    private TextField commandInput;
    @FXML
    private Button cancelRequestButton;
    @FXML
    private ChoiceBox<String> providerChoice;
    @FXML
    private ChoiceBox<AIConfig> modelChoice;

    @FXML private ScrollPane assistantScroll;
    @FXML private Button refreshModelsButton;
    @FXML private Button configureAIButton;
    /** Programmatic picker synchronization must not trigger configuration writes. */
    private boolean updatingModels;
    private volatile boolean disposed;
    /** Invalidates callbacks already queued on the FX thread when a request loses ownership. */
    private final java.util.concurrent.atomic.AtomicLong requestGeneration = new java.util.concurrent.atomic.AtomicLong();
    private AIAdapter aiAdapter;
    private AIService aiService;
    private final com.runzhi.aishell.ai.stream.AIStreamingExecutor streamingExecutor =
            new com.runzhi.aishell.ai.stream.AIStreamingExecutor();
    private volatile com.runzhi.aishell.ai.stream.AIRequestHandle activeRequest;

    /** 与 SettingsAIController 同一偏好节点，读取「AI 安全确认」开关。 */
    private static final java.util.prefs.Preferences SAFETY_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(SettingsAIController.class);

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        // 确保服务已初始化
        if (aiService == null) {
            moduleManager = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            aiService = moduleManager.getAiService();
        }
        
        // AIAssistantPanelController特有的初始化
        initializeAIAdapterIfNeeded();
        // 填充供应商/模型切换器（面板入口就地切换，不必再开配置对话框）
        refreshModelList();
    }

    /** 刷新供应商与模型下拉列表，并定位到当前配置 */
    @FXML
    private void refreshModelList() {
        AIConfigManager manager = AIConfigManager.getInstance();
        populateModels(manager.getAllConfigs(), manager.getCurrentConfig());
    }

    /** Render configurations by identity; refreshing must not switch the live configuration. */
    void populateModels(List<AIConfig> configs, AIConfig current) {
        if (providerChoice == null || modelChoice == null) return;
        updatingModels = true;
        try {
            modelChoice.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(AIConfig config) {
                    return config == null ? "" : config.getName() + " · "
                            + (config.getModel() == null ? "" : config.getModel());
                }
                @Override public AIConfig fromString(String value) { return null; }
            });
            providerChoice.getItems().setAll(configs.stream().map(AIConfig::getName)
                    .filter(java.util.Objects::nonNull).distinct().toList());
            modelChoice.getItems().setAll(configs);
            Tooltip profileInfo = new Tooltip();
            profileInfo.textProperty().bind(providerChoice.valueProperty());
            providerChoice.setTooltip(profileInfo);
            Tooltip modelInfo = new Tooltip();
            modelInfo.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                    () -> modelChoice.getConverter().toString(modelChoice.getValue()), modelChoice.valueProperty()));
            modelChoice.setTooltip(modelInfo);
            providerChoice.setValue(current == null ? null : current.getName());
            modelChoice.setValue(current);
        } finally { updatingModels = false; }
    }

    @FXML
    private void switchProvider() {
        if (updatingModels || disposed || providerChoice == null) return;
        String selected = providerChoice.getValue();
        if (selected == null) return;
        AIConfig target = modelChoice.getItems().stream()
                .filter(config -> selected.equals(config.getName())).findFirst().orElse(null);
        if (target != null) applyConfigChange(target);
    }

    @FXML
    private void switchModel() {
        if (updatingModels || disposed || modelChoice == null) return;
        AIConfig target = modelChoice.getValue();
        if (target != null) applyConfigChange(target);
    }

    /** Switch the selected configuration, never infer identity from a possibly duplicated model name. */
    void applyConfigChange(AIConfig target) {
        AIConfigManager manager = AIConfigManager.getInstance();
        manager.setCurrentConfig(target.getName());
        if (aiService != null) aiService.switchToConfig(target.getName());
        aiAdapter = manager.getCurrentAIAdapter();
        populateModels(manager.getAllConfigs(), target);
    }

    private void initializeAIAdapterIfNeeded() {
        // 如果AI适配器未初始化，使用AI服务获取当前AI适配器
        if (aiAdapter == null) {
            aiAdapter = aiService.isConfigured() ?
                    AIConfigManager.getInstance().getCurrentAIAdapter() : null;
        }

        if (aiAdapter == null) {
            // 如果没有配置，尝试使用默认配置
            AIConfigManager.getInstance().useFallbackConfig("默认OpenAI");
            aiAdapter = AIConfigManager.getInstance().getCurrentAIAdapter();
        }
    }

    @FXML
    private void handleCommandInput() {
        String command = commandInput.getText();
        if (command != null && !command.trim().isEmpty()) {
            AIAdapter configured = AIConfigManager.getInstance().getCurrentAIAdapter();
            if (configured != null) aiAdapter = configured;
            if (aiAdapter == null || !aiAdapter.isConfigured()) {
                appendToTerminal(I18n.tr("assistant.notConfigured") + "\n", "#ff0000");
                return;
            }
            startRequest(command, aiAdapter);
            commandInput.clear();
        }
    }

    /** Each request owns its callbacks; cancellation and clearing invalidate all queued events. */
    void startRequest(String command, AIAdapter adapter) {
        if (disposed) return;
        if (adapter == null || command == null || command.isBlank())
            throw new IllegalArgumentException("A configured adapter and nonempty prompt are required");
        cancelActiveRequest();
        long generation = requestGeneration.get();
        appendToTerminal(I18n.tr("assistant.user", command) + "\n", "#E6EDF3");
        setRequestRunning(true);
        StringBuilder fullResponse = new StringBuilder();
        activeRequest = streamingExecutor.submit(adapter, command, event -> Platform.runLater(() -> {
            if (disposed || generation != requestGeneration.get()) return;
            switch (event.type()) {
                case STARTED -> appendToTerminal("AI: ", "#A371F7");
                case DELTA -> { fullResponse.append(event.text()); appendToTerminal(event.text(), "#E6EDF3"); }
                case COMPLETED -> {
                    appendToTerminal("\n", "#E6EDF3");
                    String commandText = extractShellCommand(fullResponse.toString());
                    if (commandText != null) appendCommandCard(commandText);
                    setRequestRunning(false);
                }
                case CANCELLED -> { appendToTerminal(I18n.tr("assistant.stopped") + "\n", "#D29922"); setRequestRunning(false); }
                case ERROR -> { appendToTerminal(I18n.tr("assistant.error", event.text()) + "\n", "#F85149"); setRequestRunning(false); }
            }
        }));
    }

    @FXML
    private void openAIConfiguration() {
        SettingsDialogController.showDialog(commandInput.getScene().getWindow(), "ai");
        aiAdapter = AIConfigManager.getInstance().getCurrentAIAdapter();
        refreshModelList();
    }

    @FXML
    private void clearChat() {
        cancelActiveRequest();
        setRequestRunning(false);
        terminalOutput.getChildren().clear();
    }

    @FXML
    private void cancelRequest() {
        cancelActiveRequest();
        appendToTerminal(I18n.tr("assistant.stopped") + "\n", "#D29922");
        setRequestRunning(false);
    }

    private void cancelActiveRequest() {
        requestGeneration.incrementAndGet();
        com.runzhi.aishell.ai.stream.AIRequestHandle request = activeRequest;
        if (request != null && !request.isDone()) request.cancel();
        activeRequest = null;
    }

    /**
     * 窗口关闭时由 NavigationManager 调用：取消正在进行的流式请求，
     * 避免它在后台继续消耗 API 配额、并往已分离的 TextFlow 回调写入。
     */
    @Override
    public void onWindowClosing() {
        disposed = true;
        cancelActiveRequest();
    }

    private void setRequestRunning(boolean running) {
        cancelRequestButton.setVisible(running);
        cancelRequestButton.setManaged(running);
        commandInput.setDisable(running);
        if (providerChoice != null) providerChoice.setDisable(running);
        if (modelChoice != null) modelChoice.setDisable(running);
        if (refreshModelsButton != null) refreshModelsButton.setDisable(running);
        if (configureAIButton != null) configureAIButton.setDisable(running);
    }

    /** Append on FX directly so a cancelled request cannot leave a second layer of queued text writes. */
    private void appendToTerminal(String text, String color) {
        String semantic = switch (color.toLowerCase(java.util.Locale.ROOT)) {
            case "#e6edf3", "#a371f7" -> "-fx-text-primary";
            case "#8b949e" -> "-fx-text-secondary";
            case "#ff0000", "#f85149" -> "-fx-danger-color";
            case "#ff9900", "#d29922" -> "-fx-warning-color";
            case "#56d364", "#3fb950" -> "-fx-success-color";
            default -> color;
        };
        long generation = requestGeneration.get();
        Runnable append = () -> {
            if (disposed || generation != requestGeneration.get()) return;
            javafx.scene.text.Text node = new javafx.scene.text.Text(text);
            node.setStyle("-fx-fill: " + semantic + ";");
            terminalOutput.getChildren().add(node);
        };
        if (Platform.isFxApplicationThread()) append.run(); else Platform.runLater(append);
    }

    /**
     * 追加 AI Command Card（UI 优化方案 V1 §18）
     * <p>
     * 命令独立于普通正文显示，卡片内提供复制 / 发送到终端 / 执行，
     * 并按风险等级（只读 / 中 / 高）给状态色与提示文案。危险命令
     * 只有「确认后执行」，避免误触。
     */
    private void appendCommandCard(String command) {
        CommandRisk risk = assessRisk(command);

        VBox card = new VBox(6);
        card.getStyleClass().add("ai-command-card");
        card.setMinWidth(0);
        card.prefWidthProperty().bind(terminalOutput.widthProperty());
        card.maxWidthProperty().bind(terminalOutput.widthProperty());

        // 标题行：Shell + 风险标签
        javafx.scene.control.Label titleLabel = new javafx.scene.control.Label("Shell");
        titleLabel.getStyleClass().add("ai-command-title");

        javafx.scene.control.Label riskLabel = new javafx.scene.control.Label(I18n.tr("assistant.risk", I18n.tr("agent.risk." + risk.label)));
        riskLabel.getStyleClass().add("ai-command-risk");
        riskLabel.setStyle("-fx-text-fill: " + risk.color + ";");

        javafx.scene.layout.HBox header = new javafx.scene.layout.HBox(10, titleLabel, riskLabel);
        card.getChildren().add(header);

        // 命令正文（等宽字体）
        javafx.scene.control.Label cmdLabel = new javafx.scene.control.Label("$ " + command);
        cmdLabel.getStyleClass().add("ai-command-text");
        cmdLabel.setWrapText(true);
        card.getChildren().add(cmdLabel);

        // 操作行
        javafx.scene.control.Button copyBtn = new javafx.scene.control.Button(I18n.tr("agent.copy"));
        copyBtn.getStyleClass().add("ai-command-btn");
        copyBtn.setOnAction(e -> {
            boolean copied = ClipboardUtils.copyText(command);
            appendToTerminal(I18n.tr(copied ? "assistant.copied" : "agent.copyFailed") + "\n",
                    copied ? "#8B949E" : "#F85149");
        });

        javafx.scene.control.Button sendBtn = new javafx.scene.control.Button(I18n.tr("agent.send"));
        sendBtn.getStyleClass().add("ai-command-btn");
        sendBtn.setOnAction(e -> sendCommandToTerminal(command));

        // 统一走 executeCommandInTerminal：沙箱校验 + 风险分级 + 审批 + 审计。
        // 高危按钮单独标红并提示需要二次确认，但门禁逻辑只有一处实现。
        javafx.scene.control.Button execBtn;
        if (risk == CommandRisk.HIGH) {
            execBtn = new javafx.scene.control.Button(I18n.tr("assistant.executeHigh"));
            execBtn.getStyleClass().add("ai-command-btn-danger");
        } else {
            execBtn = new javafx.scene.control.Button(I18n.tr("assistant.execute"));
            execBtn.getStyleClass().add("ai-command-btn");
        }
        execBtn.setOnAction(e -> executeCommandInTerminal(command));

        card.getChildren().add(new javafx.scene.layout.FlowPane(8, 6, copyBtn, sendBtn, execBtn));

        terminalOutput.getChildren().add(card);
        appendToTerminal("\n", "#E6EDF3");
    }

    /** 从 AI 回复中提取 shell 命令（优先 ```bash 代码块，其次 $ 前缀行） */
    private String extractShellCommand(String response) {
        if (response == null || response.isBlank()) return null;
        // 1. ```bash ... ``` 或 ```sh ... ``` 代码块
        java.util.regex.Matcher block = java.util.regex.Pattern.compile(
                "```(?:bash|sh|shell|zsh)?\\s*\\r?\\n([\\s\\S]*?)```").matcher(response);
        if (block.find()) {
            String cmd = block.group(1).trim();
            if (!cmd.isBlank()) return cmd.split("\\r?\\n")[0].trim();
        }
        // 2. 独立的 $ 命令行
        for (String line : response.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("$ ")) {
                String cmd = trimmed.substring(2).trim();
                if (!cmd.isBlank()) return cmd;
            }
        }
        return null;
    }

    /** 命令风险分级（§18,§19） */
    private enum CommandRisk {
        READ_ONLY("readonly", "-fx-success-color"),
        MEDIUM("medium", "-fx-warning-color"),
        HIGH("high", "-fx-danger-color");

        final String label;
        final String color;
        CommandRisk(String label, String color) { this.label = label; this.color = color; }
    }

    private CommandRisk assessRisk(String command) {
        // 统一使用主风险评估器，避免同一命令在不同入口风险等级不一致。
        return switch (CommandRiskAssessor.assess(command)) {
            case LOW -> CommandRisk.READ_ONLY;
            case MEDIUM -> CommandRisk.MEDIUM;
            case HIGH -> CommandRisk.HIGH;
        };
    }

    /** 取当前选中终端 Tab 的输入框 */
    private javafx.scene.control.TextField findCurrentTerminalInput() {
        Object pane = com.runzhi.aishell.common.util.ParameterManager.getInstance()
                .get("terminalTabPane");
        com.runzhi.aishell.service.TerminalTabService tabService = com.runzhi.aishell.common.ai.AIShellModuleManager
                .getInstance().getTerminalTabService();
        if (pane instanceof javafx.scene.control.TabPane tabPane && tabService != null) {
            javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected != null) {
                return tabService.getTabToInput().get(selected);
            }
        }
        return null;
    }

    /** 把命令发送到当前终端输入框（不自动执行，用户可再修改） */
    private void sendCommandToTerminal(String command) {
        javafx.scene.control.TextField input = findCurrentTerminalInput();
        if (input != null) {
            input.setText(command);
            appendToTerminal(I18n.tr("assistant.sent") + "\n", "#8B949E");
        } else {
            appendToTerminal(I18n.tr("assistant.noTarget") + "\n", "#F85149");
        }
    }

    /**
     * 在当前终端执行命令：沙箱校验 → 风险分级 → 必要时人工确认 → 审计记录。
     * <p>
     * 中/高风险一律需要显式确认（高危额外二次确认）；只读命令在「AI 安全确认」
     * 开关（{@code ai.safetyconfirm}，默认开）开启时也需要确认。全部执行尝试入审计。
     */
    private void executeCommandInTerminal(String command) {
        javafx.scene.control.TextField input = findCurrentTerminalInput();
        if (input == null) {
            appendToTerminal(I18n.tr("assistant.noExecutionTarget") + "\n", "#F85149");
            return;
        }
        com.runzhi.aishell.service.TerminalCommandService commandService =
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getTerminalCommandService();
        Object pane = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("terminalTabPane");
        if (commandService == null || !(pane instanceof javafx.scene.control.TabPane tabPane)) {
            appendToTerminal(I18n.tr("assistant.noService") + "\n", "#F85149");
            return;
        }
        javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) {
            appendToTerminal(I18n.tr("assistant.noExecutionTarget") + "\n", "#F85149");
            return;
        }
        String tabId = selected.getId() == null ? "unknown" : selected.getId();
        CommandRisk risk = assessRisk(command);
        com.runzhi.aishell.agent.AgentAuditLogger audit =
                com.runzhi.aishell.agent.AgentAuditLogger.getInstance();

        com.runzhi.aishell.agent.AgentSandboxPolicy.Verdict verdict =
                com.runzhi.aishell.agent.AgentSandboxPolicy.validate(command);
        if (!verdict.allowed()) {
            audit.record("panel_sandbox_denied", tabId, agentRisk(risk), command, "denied");
            appendToTerminal(I18n.tr("assistant.denied", verdict.reason()) + "\n", "#F85149");
            return;
        }

        if (needsConfirmation(risk)) {
            audit.record("panel_proposed", tabId, agentRisk(risk), command, "pending");
            boolean high = risk == CommandRisk.HIGH;
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    high ? javafx.scene.control.Alert.AlertType.WARNING
                            : javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    (I18n.tr(high ? "agent.highWarning" : "agent.confirmHeader") + "\n\n") + "$ " + command,
                    javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
            confirm.setTitle(I18n.tr(high ? "agent.highTitle" : "agent.confirmTitle"));
            ((Button) confirm.getDialogPane().lookupButton(ButtonType.OK)).setText(I18n.tr("agent.execute"));
            ((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setText(I18n.tr("settings.cancel"));
            confirm.showAndWait().ifPresentOrElse(btn -> {
                if (btn == javafx.scene.control.ButtonType.OK) {
                    audit.record("panel_approved", tabId, agentRisk(risk), command, "approved");
                    runCommand(command, selected, input, commandService, tabId, risk);
                } else {
                    audit.record("panel_rejected", tabId, agentRisk(risk), command, "rejected");
                    appendToTerminal(I18n.tr("assistant.cancelled", command) + "\n", "#D29922");
                }
            }, () -> audit.record("panel_rejected", tabId, agentRisk(risk), command, "dismissed"));
            return;
        }

        audit.record("panel_approved", tabId, agentRisk(risk), command, "auto-approved");
        runCommand(command, selected, input, commandService, tabId, risk);
    }

    /** 实际把命令送到终端执行并补一条执行审计。 */
    private void runCommand(String command, javafx.scene.control.Tab selected,
                            javafx.scene.control.TextField input,
                            com.runzhi.aishell.service.TerminalCommandService commandService,
                            String tabId, CommandRisk risk) {
        input.setText(command);
        commandService.executeShellCommand(selected, command);
        com.runzhi.aishell.agent.AgentAuditLogger.getInstance().record(
                "panel_executed", tabId, agentRisk(risk), command, "executed");
        appendToTerminal(I18n.tr("assistant.executed", command) + "\n", "#3FB950");
    }

    /** 面板风险枚举对齐到审计用的统一分级。 */
    private com.runzhi.aishell.agent.CommandRisk agentRisk(CommandRisk risk) {
        return switch (risk) {
            case READ_ONLY -> com.runzhi.aishell.agent.CommandRisk.LOW;
            case MEDIUM -> com.runzhi.aishell.agent.CommandRisk.MEDIUM;
            case HIGH -> com.runzhi.aishell.agent.CommandRisk.HIGH;
        };
    }

    /** 中高风险必须确认；只读命令在「AI 安全确认」开启时也需要确认（默认开）。 */
    private boolean needsConfirmation(CommandRisk risk) {
        if (risk != CommandRisk.READ_ONLY) return true;
        return SAFETY_PREFS.getBoolean("ai.safetyconfirm", true);
    }

    private void showAlert(String message) {
        DialogUtils.showInfoDialog(I18n.tr("assistant.notice"), null, message);
    }

    // Getter方法，供主控制器使用
    public TextFlow getTerminalOutput() {
        return terminalOutput;
    }

    public AIAdapter getAiAdapter() {
        return aiAdapter;
    }

    public void setAiAdapter(AIAdapter aiAdapter) {
        this.aiAdapter = aiAdapter;
    }

    public AIService getAiService() {
        return aiService;
    }

    public void setAiService(AIService aiService) {
        this.aiService = aiService;
    }
}
