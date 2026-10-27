/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.ai.AIService;
import com.runzhi.aishell.agent.CommandRiskAssessor;
import com.runzhi.aishell.common.util.DialogUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
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
    private ChoiceBox<String> modelChoice;

    private AIAdapter aiAdapter;
    private AIService aiService;
    private com.runzhi.aishell.common.ai.AIHelperService aiHelperService;
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
        // 初始化AI助手服务
        aiHelperService = new com.runzhi.aishell.common.ai.AIHelperService();
        // 填充供应商/模型切换器（面板入口就地切换，不必再开配置对话框）
        refreshModelList();
    }

    /** 刷新供应商与模型下拉列表，并定位到当前配置 */
    @FXML
    private void refreshModelList() {
        if (providerChoice == null || modelChoice == null) {
            return;
        }
        AIConfigManager configManager = AIConfigManager.getInstance();
        List<AIConfig> configs = configManager.getAllConfigs();

        // 供应商 = 去重后的配置名（一个供应商名下可能有多个模型配置）
        providerChoice.getItems().clear();
        modelChoice.getItems().clear();
        java.util.Set<String> seenProviders = new java.util.LinkedHashSet<>();
        for (AIConfig config : configs) {
            if (config.getName() != null) {
                seenProviders.add(config.getName());
            }
        }
        providerChoice.getItems().addAll(seenProviders);

        // 模型列表显示 "配置名 · 模型"，便于区分同供应商不同模型
        for (AIConfig config : configs) {
            if (config.getModel() != null) {
                modelChoice.getItems().add(config.getModel());
            }
        }

        // 定位到当前配置
        AIConfig current = configManager.getCurrentConfig();
        if (current != null) {
            if (current.getName() != null) {
                providerChoice.setValue(current.getName());
            }
            if (current.getModel() != null) {
                modelChoice.setValue(current.getModel());
            }
        }
    }

    /** 供应商切换：定位到该供应商下第一个配置 */
    @FXML
    private void switchProvider() {
        if (providerChoice == null) return;
        String selected = providerChoice.getValue();
        if (selected == null) return;

        AIConfigManager configManager = AIConfigManager.getInstance();
        AIConfig target = null;
        for (AIConfig config : configManager.getAllConfigs()) {
            if (selected.equals(config.getName())) {
                target = config;
                break;
            }
        }
        if (target == null) {
            appendToTerminal("未找到供应商配置: " + selected + "\n", "#ff9900");
            return;
        }
        applyConfigChange(target);
    }

    /** 模型切换：按模型名找到对应配置并切换 */
    @FXML
    private void switchModel() {
        if (modelChoice == null) return;
        String selected = modelChoice.getValue();
        if (selected == null) return;

        AIConfigManager configManager = AIConfigManager.getInstance();
        AIConfig target = null;
        // 优先匹配当前供应商下的模型，其次全局匹配
        String currentProvider = providerChoice != null ? providerChoice.getValue() : null;
        if (currentProvider != null) {
            for (AIConfig config : configManager.getAllConfigs()) {
                if (currentProvider.equals(config.getName()) && selected.equals(config.getModel())) {
                    target = config;
                    break;
                }
            }
        }
        if (target == null) {
            for (AIConfig config : configManager.getAllConfigs()) {
                if (selected.equals(config.getModel())) {
                    target = config;
                    break;
                }
            }
        }
        if (target == null) {
            appendToTerminal("未找到模型配置: " + selected + "\n", "#ff9900");
            return;
        }
        applyConfigChange(target);
    }

    /** 应用配置切换：更新 ConfigManager、AI 服务与本地适配器引用 */
    private void applyConfigChange(AIConfig target) {
        AIConfigManager configManager = AIConfigManager.getInstance();
        configManager.setCurrentConfig(target.getName());
        if (aiService != null) {
            aiService.switchToConfig(target.getName());
        }
        aiAdapter = configManager.getCurrentAIAdapter();
        // 同步两个下拉的显示，避免出现不一致
        if (providerChoice != null) providerChoice.setValue(target.getName());
        if (modelChoice != null) modelChoice.setValue(target.getModel());
        appendToTerminal("已切换模型：" + target.getName() + " · "
                + (target.getModel() == null ? "" : target.getModel()) + "\n", "#56d364");
    }

    private void initializeAIAdapterIfNeeded() {
        // 如果AI适配器未初始化，使用AI服务获取当前AI适配器
        if (aiAdapter == null) {
            aiAdapter = aiService.isConfigured() ?
                    AIConfigManager.getInstance().getCurrentAIAdapter() : null;
        }

        if (aiAdapter == null) {
            // 如果没有配置，尝试使用默认配置
            AIConfigManager.getInstance().setCurrentConfig("默认OpenAI");
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
                appendToTerminal("AI错误: 请先配置可用的 AI 服务\n", "#ff0000");
                return;
            }
            // 显示用户输入
            appendToTerminal("用户: " + command + "\n", "#E6EDF3");

            cancelActiveRequest();
            setRequestRunning(true);
            // 累积完整回复，完成后用于提取 shell 命令渲染 Command Card（§18）
            StringBuilder fullResponse = new StringBuilder();
            activeRequest = streamingExecutor.submit(aiAdapter, command, event -> Platform.runLater(() -> {
                switch (event.type()) {
                    // AI 身份标识用紫色（-fx-ai-color），普通正文用 Text Primary（§24）
                    case STARTED -> appendToTerminal("AI: ", "#A371F7");
                    case DELTA -> {
                        fullResponse.append(event.text());
                        appendToTerminal(event.text(), "#E6EDF3");
                    }
                    case COMPLETED -> {
                        appendToTerminal("\n", "#E6EDF3");
                        // 提取回复中的 shell 命令并渲染为可操作卡片
                        String extracted = extractShellCommand(fullResponse.toString());
                        if (extracted != null) {
                            appendCommandCard(extracted);
                        }
                        setRequestRunning(false);
                    }
                    case CANCELLED -> {
                        appendToTerminal("[已停止]\n", "#D29922");
                        setRequestRunning(false);
                    }
                    case ERROR -> {
                        appendToTerminal("AI错误: " + event.text() + "\n", "#F85149");
                        setRequestRunning(false);
                    }
                }
            }));

            commandInput.clear();
        }
    }

    @FXML
    private void openAIConfiguration() {
        // 创建配置对话框
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("AI配置");
        dialog.setHeaderText("配置AI服务");

        // 设置按钮类型
        ButtonType saveButtonType = new ButtonType("保存", ButtonType.OK.getButtonData());
        ButtonType cancelButtonType = new ButtonType("取消", ButtonType.CANCEL.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, cancelButtonType);

        // 创建输入字段
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        // AI服务选择 - 包含所有支持的AI服务
        ChoiceBox<String> aiServiceChoice = new ChoiceBox<>();
        aiServiceChoice.getItems().addAll(
                "OpenAI",
                "Claude",
                "Ollama",
                "Gemini",
                "Qwen",
                "ERNIE Bot",
                "Zhipu",
                "Moonshot",
                "MiniMax",
                "Mistral",
                "DeepSeek",
                "Xiaomimimo"
        );
        aiServiceChoice.setValue("OpenAI");

        // 配置名称
        TextField configNameField = new TextField();
        configNameField.setPromptText("配置名称");

        // API密钥
        TextField apiKeyField = new TextField();
        apiKeyField.setPromptText("输入API密钥");

        // 模型选择
        TextField modelField = new TextField();
        modelField.setPromptText("模型名称");

        // 基础URL
        TextField baseUrlField = new TextField();
        baseUrlField.setPromptText("基础URL");

        // AI配置管理器
        AIConfigManager configManager = AIConfigManager.getInstance();

        // 添加配置切换功能
        ChoiceBox<String> configChoice = new ChoiceBox<>();
        List<AIConfig> allConfigs = configManager.getAllConfigs();
        for (AIConfig config : allConfigs) {
            configChoice.getItems().add(config.getName());
        }

        // 当前配置
        AIConfig currentConfig = configManager.getCurrentConfig();
        if (currentConfig != null) {
            configChoice.setValue(currentConfig.getName());
        }

        // 添加配置选择事件监听
        configChoice.setOnAction(e -> {
            String selectedConfigName = configChoice.getValue();
            if (selectedConfigName != null) {
                AIConfig selectedConfig = configManager.getConfigByName(selectedConfigName);
                if (selectedConfig != null) {
                    configManager.setCurrentConfig(selectedConfigName);
                    aiAdapter = configManager.getCurrentAIAdapter();
                    aiService.switchToConfig(selectedConfigName);
                    // 更新界面字段
                    configNameField.setText(selectedConfig.getName());
                    aiServiceChoice.setValue(selectedConfig.getType());
                    apiKeyField.setText(selectedConfig.getApiKey());
                    modelField.setText(selectedConfig.getModel());
                    baseUrlField.setText(selectedConfig.getBaseUrl());
                }
            }
        });

        // 添加AI服务类型选择事件监听
        aiServiceChoice.setOnAction(e -> {
            String selectedType = aiServiceChoice.getValue();
            if ("OpenAI".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.openai.com/v1/chat/completions");
                modelField.setPromptText("gpt-3.5-turbo");
                apiKeyField.setPromptText("sk-... 开头的API密钥");
            } else if ("Claude".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.anthropic.com/v1/messages");
                modelField.setPromptText("claude-3-sonnet-20240229");
                apiKeyField.setPromptText("sk-ant-... 开头的API密钥");
            } else if ("Ollama".equals(selectedType)) {
                baseUrlField.setPromptText("http://localhost:11434/api/generate");
                modelField.setPromptText("llama2");
                apiKeyField.setPromptText("(可选)认证密钥");
            } else if ("Gemini".equals(selectedType)) {
                baseUrlField.setPromptText("https://generativelanguage.googleapis.com/v1beta/models/");
                modelField.setPromptText("gemini-pro");
                apiKeyField.setPromptText("AIza... 开头的API密钥");
            } else if ("Qwen".equals(selectedType)) {
                baseUrlField.setPromptText("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
                modelField.setPromptText("qwen-max");
                apiKeyField.setPromptText("sk-... 开头的API密钥");
            } else if ("ERNIE Bot".equals(selectedType)) {
                baseUrlField.setPromptText("https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/");
                modelField.setPromptText("ernie-bot-4.5");
                apiKeyField.setPromptText("APIKey:SecretKey 格式");
            } else if ("Zhipu".equals(selectedType)) {
                baseUrlField.setPromptText("https://open.bigmodel.cn/api/paas/v4/chat/completions");
                modelField.setPromptText("glm-4");
                apiKeyField.setPromptText("API密钥");
            } else if ("Moonshot".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.moonshot.cn/v1/chat/completions");
                modelField.setPromptText("moonshot-v1-8k");
                apiKeyField.setPromptText("sk-... 开头的API密钥");
            } else if ("MiniMax".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.minimaxi.com/v1/chat/completions");
                modelField.setPromptText("abab5.5-chat");
                apiKeyField.setPromptText("APIKey:GroupID 格式");
            } else if ("Mistral".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.mistral.ai/v1/chat/completions");
                modelField.setPromptText("mistral-small-latest");
                apiKeyField.setPromptText("Bearer... 格式的API密钥");
            } else if ("DeepSeek".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.deepseek.com/v1/chat/completions");
                modelField.setPromptText("deepseek-chat");
                apiKeyField.setPromptText("sk-... 开头的API密钥");
            } else if ("Xiaomimimo".equals(selectedType)) {
                baseUrlField.setPromptText("https://api.xiaomimimo.com/v1/chat/completions");
                modelField.setPromptText("mimo-large-2024");
                apiKeyField.setPromptText("API密钥");
            }
        });

        grid.add(new Label("现有配置:"), 0, 0);
        grid.add(configChoice, 1, 0);
        grid.add(new Label("配置名称:"), 0, 1);
        grid.add(configNameField, 1, 1);
        grid.add(new Label("AI服务:"), 0, 2);
        grid.add(aiServiceChoice, 1, 2);
        grid.add(new Label("API密钥:"), 0, 3);
        grid.add(apiKeyField, 1, 3);
        grid.add(new Label("模型:"), 0, 4);
        grid.add(modelField, 1, 4);
        grid.add(new Label("基础URL:"), 0, 5);
        grid.add(baseUrlField, 1, 5);

        dialog.getDialogPane().setContent(grid);

        // 启用/禁用保存按钮
        Platform.runLater(() -> configNameField.requestFocus());

        // 处理结果
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                // 创建或更新AI配置
                if (configNameField.getText() != null && !configNameField.getText().trim().isEmpty()) {
                    AIConfig newConfig = new AIConfig();
                    newConfig.setName(configNameField.getText().trim());
                    newConfig.setType(aiServiceChoice.getValue());
                    newConfig.setApiKey(apiKeyField.getText().trim());
                    newConfig.setModel(modelField.getText().trim());
                    newConfig.setBaseUrl(baseUrlField.getText().trim());

                    // 保存配置
                    configManager.addConfig(newConfig);
                    configManager.setCurrentConfig(newConfig.getName());
                    aiAdapter = configManager.getCurrentAIAdapter();
                    aiService.updateAdapter(newConfig);

                    // 刷新供应商/模型切换条，否则切换条还停在旧列表，
                    // 用户会以为「模型没保存」
                    refreshModelList();

                    appendToTerminal("AI配置已保存并激活: " + newConfig.getName() + "\n", "#00ffff");
                    return newConfig.getName();
                } else {
                    showAlert("请输入配置名称");
                    return null;
                }
            }
            return null;
        });

        Platform.runLater(() -> {
            dialog.showAndWait();
        });
    }

    @FXML
    private void clearChat() {
        terminalOutput.getChildren().clear();
    }

    @FXML
    private void cancelRequest() {
        cancelActiveRequest();
        setRequestRunning(false);
    }

    private void cancelActiveRequest() {
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
        cancelActiveRequest();
    }

    private void setRequestRunning(boolean running) {
        cancelRequestButton.setVisible(running);
        cancelRequestButton.setManaged(running);
        commandInput.setDisable(running);
    }

    private void appendToTerminal(String text, String color) {
        com.runzhi.aishell.utils.ShellUtils.appendToTextFlow(terminalOutput, text, color);

        // 自动滚动到底部
        Platform.runLater(() -> {
            terminalOutput.applyCss();
            terminalOutput.layout();
        });
    }

    /**
     * 追加 AI Command Card（UI 优化方案 V1 §18）
     * <p>
     * 命令独立于普通正文显示，卡片内提供复制 / 发送到终端 / 执行，
     * 并按风险等级（只读 / 中 / 高）给状态色与提示文案。危险命令
     * 只有「确认后执行」，避免误触。
     */
    private void appendCommandCard(String command) {
        Platform.runLater(() -> {
            CommandRisk risk = assessRisk(command);

            VBox card = new VBox(6);
            card.getStyleClass().add("ai-command-card");

            // 标题行：Shell + 风险标签
            javafx.scene.control.Label titleLabel = new javafx.scene.control.Label("Shell");
            titleLabel.getStyleClass().add("ai-command-title");

            javafx.scene.control.Label riskLabel = new javafx.scene.control.Label("风险：" + risk.label);
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
            javafx.scene.control.Button copyBtn = new javafx.scene.control.Button("复制");
            copyBtn.getStyleClass().add("ai-command-btn");
            copyBtn.setOnAction(e -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(command);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                appendToTerminal("已复制到剪贴板\n", "#8B949E");
            });

            javafx.scene.control.Button sendBtn = new javafx.scene.control.Button("发送到终端");
            sendBtn.getStyleClass().add("ai-command-btn");
            sendBtn.setOnAction(e -> sendCommandToTerminal(command));

            // 统一走 executeCommandInTerminal：沙箱校验 + 风险分级 + 审批 + 审计。
            // 高危按钮单独标红并提示需要二次确认，但门禁逻辑只有一处实现。
            javafx.scene.control.Button execBtn;
            if (risk == CommandRisk.HIGH) {
                execBtn = new javafx.scene.control.Button("⚠ 确认后执行");
                execBtn.getStyleClass().add("ai-command-btn-danger");
            } else {
                execBtn = new javafx.scene.control.Button("▶ 执行");
                execBtn.getStyleClass().add("ai-command-btn");
            }
            execBtn.setOnAction(e -> executeCommandInTerminal(command));

            card.getChildren().add(new javafx.scene.layout.HBox(8, copyBtn, sendBtn, execBtn));

            terminalOutput.getChildren().add(card);
            appendToTerminal("\n", "#E6EDF3");
        });
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
        READ_ONLY("只读", "#3FB950"),
        MEDIUM("中", "#D29922"),
        HIGH("高", "#F85149");

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
            appendToTerminal("已发送到终端输入框\n", "#8B949E");
        } else {
            appendToTerminal("没有活动的终端可发送\n", "#F85149");
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
            appendToTerminal("没有活动的终端可执行\n", "#F85149");
            return;
        }
        com.runzhi.aishell.service.TerminalCommandService commandService =
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getTerminalCommandService();
        Object pane = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("terminalTabPane");
        if (commandService == null || !(pane instanceof javafx.scene.control.TabPane tabPane)) {
            appendToTerminal("终端服务不可用，无法执行\n", "#F85149");
            return;
        }
        javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) {
            appendToTerminal("没有活动的终端可执行\n", "#F85149");
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
            appendToTerminal("沙箱拒绝命令：" + verdict.reason() + "\n", "#F85149");
            return;
        }

        if (needsConfirmation(risk)) {
            audit.record("panel_proposed", tabId, agentRisk(risk), command, "pending");
            boolean high = risk == CommandRisk.HIGH;
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    high ? javafx.scene.control.Alert.AlertType.WARNING
                            : javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    (high ? "高危命令，可能造成不可恢复的变更，确认执行？\n\n"
                            : "确认执行该命令？\n\n") + "$ " + command,
                    javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
            confirm.setTitle(high ? "高危命令二次确认" : "命令确认");
            confirm.showAndWait().ifPresentOrElse(btn -> {
                if (btn == javafx.scene.control.ButtonType.OK) {
                    audit.record("panel_approved", tabId, agentRisk(risk), command, "approved");
                    runCommand(command, selected, input, commandService, tabId, risk);
                } else {
                    audit.record("panel_rejected", tabId, agentRisk(risk), command, "rejected");
                    appendToTerminal("已取消执行：" + command + "\n", "#D29922");
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
        appendToTerminal("已执行: " + command + "\n", "#3FB950");
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
        DialogUtils.showInfoDialog("提示", null, message);
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
