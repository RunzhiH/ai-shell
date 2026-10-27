/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.config.AppConstants;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import lombok.extern.slf4j.Slf4j;

import java.util.prefs.Preferences;

/**
 * AI 设置控制器
 */
@Slf4j
public class SettingsAIController {
    
    // AI 模型配置
    @FXML private ComboBox<String> configProfileCombo;
    @FXML private ComboBox<String> modelCombo;
    @FXML private ComboBox<String> providerCombo;
    @FXML private PasswordField apiKeyField;
    @FXML private TextField apiEndpointField;
    @FXML private Button testConnectionButton;
    @FXML private Button fetchModelsButton;
    
    // 对话设置
    @FXML private Slider temperatureSlider;
    @FXML private Label temperatureValue;
    @FXML private Spinner<Integer> maxTokensSpinner;
    @FXML private Spinner<Integer> contextLengthSpinner;
    @FXML private CheckBox streamingCheck;
    
    // 命令生成设置
    @FXML private ComboBox<String> shellTypeCombo;
    @FXML private ComboBox<String> osTypeCombo;
    @FXML private CheckBox autoExplainCheck;
    @FXML private CheckBox safetyConfirmCheck;
    
    // 高级设置
    @FXML private Spinner<Integer> timeoutSpinner;
    @FXML private Spinner<Integer> retryCountSpinner;
    @FXML private CheckBox useProxyCheck;
    @FXML private CheckBox logRequestsCheck;
    
    // Preferences 用于存储设置
    private final Preferences prefs = Preferences.userNodeForPackage(SettingsAIController.class);
    
    /**
     * 初始化方法
     */
    @FXML
    public void initialize() {
        log.info("初始化 AI 设置页面");
        
        // 加载保存的设置
        loadSettings();
        loadSavedProfiles();
        
        // 监听温度滑块变化
        temperatureSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            temperatureValue.setText(String.format("%.1f", newVal));
        });
        providerCombo.valueProperty().addListener((obs, oldVal, provider) -> applyProviderDefaults(provider));
        configProfileCombo.valueProperty().addListener((obs, oldVal, name) -> loadSavedProfile(name));
    }

    private void loadSavedProfiles() {
        com.runzhi.aishell.ai.AIConfigManager manager = com.runzhi.aishell.ai.AIConfigManager.getInstance();
        configProfileCombo.getItems().setAll(manager.getAllConfigs().stream()
                .map(com.runzhi.aishell.ai.AIConfig::getName).toList());
        com.runzhi.aishell.ai.AIConfig current = manager.getCurrentConfig();
        if (current != null) {
            configProfileCombo.setValue(current.getName());
            applySavedProfile(current);
        } else {
            configProfileCombo.setValue("用户设置");
        }
    }

    private void loadSavedProfile(String name) {
        if (name == null || name.isBlank()) return;
        com.runzhi.aishell.ai.AIConfig config = com.runzhi.aishell.ai.AIConfigManager
                .getInstance().getConfigByName(name);
        if (config != null) applySavedProfile(config);
    }

    private void applySavedProfile(com.runzhi.aishell.ai.AIConfig config) {
        providerCombo.setValue(mapConfigTypeToProvider(config.getType()));
        apiKeyField.setText(decryptConfigKey(config.getApiKey()));
        apiEndpointField.setText(config.getBaseUrl() == null ? "" : config.getBaseUrl());
        modelCombo.setValue(config.getModel());
        temperatureSlider.setValue(config.getTemperature());
        if (config.getMaxTokens() > 0) maxTokensSpinner.getValueFactory().setValue(config.getMaxTokens());
    }

    private static String decryptConfigKey(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            if (value.startsWith("enc:")) {
                return com.runzhi.aishell.common.util.PasswordEncryptionUtil
                        .decryptPassword(value.substring("enc:".length()));
            }
            // Preferences 保存的是不带 enc: 外层标记的 v2 密文。
            if (value.startsWith("v2:")) {
                return com.runzhi.aishell.common.util.PasswordEncryptionUtil.decryptPassword(value);
            }
        } catch (IllegalArgumentException e) {
            log.warn("AI Key 无法解密，将保持为空以避免把密文发送给供应商");
            return "";
        }
        return value;
    }
    
    /**
     * 加载设置
     */
    private void loadSettings() {
        // 设置值以 AIConfig 管理器为准：此前的 ai.* Preferences 键除 ai.safetyconfirm 外没有任何消费方，
        // saveSettings 也已把全部字段写入 AIConfig；保留两套来源只会互相漂移（2026-10-23 审计 AI-14）。
        // 模型配置字段由 loadSavedProfiles() 在本方法之后用当前配置填充。
        maxTokensSpinner.getValueFactory().setValue(com.runzhi.aishell.common.config.AppConstants.DEFAULT_MAX_TOKENS);
        contextLengthSpinner.getValueFactory().setValue(5);
        streamingCheck.setSelected(true);

        shellTypeCombo.setValue("Bash");
        osTypeCombo.setValue("Linux");
        autoExplainCheck.setSelected(true);
        // 该偏好被 AIAssistantPanelController 消费：开启后，面板执行只读命令前也会弹确认
        safetyConfirmCheck.setSelected(prefs.getBoolean("ai.safetyconfirm", true));

        // 高级设置
        timeoutSpinner.getValueFactory().setValue(60);
        retryCountSpinner.getValueFactory().setValue(2);
        useProxyCheck.setSelected(false);
        logRequestsCheck.setSelected(false);

        log.debug("AI 设置加载完成");
    }
    
    /**
     * 保存设置
     */
    public void saveSettings() {
        log.info("保存 AI 设置...");
        
        // 只持久化仍被消费的 ai.safetyconfirm；其余字段经由 AIConfig 管理器保存（2026-10-23 审计 AI-14）
        prefs.putBoolean("ai.safetyconfirm", safetyConfirmCheck.isSelected());

        com.runzhi.aishell.ai.AIConfig runtimeConfig = new com.runzhi.aishell.ai.AIConfig();
        String profileName = configProfileCombo.getEditor().getText();
        if (profileName == null || profileName.isBlank()) profileName = configProfileCombo.getValue();
        if (profileName == null || profileName.isBlank()) profileName = "用户设置";
        runtimeConfig.setName(profileName.trim());
        runtimeConfig.setType(mapProviderType(providerCombo.getValue()));
        runtimeConfig.setApiKey(apiKeyField.getText());
        runtimeConfig.setBaseUrl(normalizeProviderEndpoint(providerCombo.getValue(), apiEndpointField.getText()));
        runtimeConfig.setModel(modelCombo.getValue());
        runtimeConfig.setTemperature(temperatureSlider.getValue());
        runtimeConfig.setMaxTokens(maxTokensSpinner.getValue());
        com.runzhi.aishell.ai.AIConfigManager manager = com.runzhi.aishell.ai.AIConfigManager.getInstance();
        manager.addConfig(runtimeConfig);
        manager.setCurrentConfig(runtimeConfig.getName());
        if (!configProfileCombo.getItems().contains(runtimeConfig.getName())) {
            configProfileCombo.getItems().add(runtimeConfig.getName());
        }
        configProfileCombo.setValue(runtimeConfig.getName());
        // AIService 在应用启动时可能已经缓存了旧适配器；保存后立即刷新，
        // 让 Agent、AI 面板和终端 AI 模式使用同一份最新配置。
        com.runzhi.aishell.ai.AIService aiService =
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getAiService();
        if (aiService != null) aiService.reinitializeAdapter();
        
        log.info("AI 设置已保存");
    }
    
    /**
     * 测试连接
     */
    @FXML
    private void handleTestConnection() {
        String apiKey = apiKeyField.getText();
        String endpoint = apiEndpointField.getText();
        String model = modelCombo.getValue();
        
        if (apiKey.isEmpty()) {
            showAlert("警告", "请先输入 API Key");
            return;
        }
        
        if (endpoint == null || endpoint.isBlank() || model == null || model.isBlank()) {
            showAlert("警告", "请填写 API 端点和模型");
            return;
        }
        log.info("测试 AI 连接：provider={}, endpoint={}, model={}", providerCombo.getValue(), endpoint, model);
        testConnectionButton.setDisable(true);
        testConnectionButton.setText("测试中...");
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().submitIoTask(() -> {
            String result;
            try {
                com.runzhi.aishell.ai.AIAdapter adapter = createTestAdapter(apiKey, endpoint, model);
                result = adapter.sendQuery("Reply with exactly: OK");
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
            }
            String finalResult = result;
            javafx.application.Platform.runLater(() -> {
                testConnectionButton.setDisable(false);
                testConnectionButton.setText("测试连接");
                boolean ok = finalResult != null && !finalResult.startsWith("Error:");
                showAlert(ok ? "连接成功" : "连接失败", ok ? "模型已成功响应。" : finalResult);
            });
            return null;
        });
    }

    @FXML
    private void handleFetchModels() {
        String provider = providerCombo.getValue();
        String endpoint = apiEndpointField.getText();
        String apiKey = apiKeyField.getText();
        fetchModelsButton.setDisable(true);
        fetchModelsButton.setText("获取中...");
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().submitIoTask(() -> {
            try {
                String modelsEndpoint = toModelsEndpoint(provider, endpoint);
                if ("Gemini".equals(provider) && apiKey != null && !apiKey.isBlank()) {
                    modelsEndpoint += (modelsEndpoint.contains("?") ? "&" : "?") + "key="
                            + java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8);
                }
                java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(modelsEndpoint))
                        .timeout(java.time.Duration.ofSeconds(30)).GET();
                if ("Anthropic".equals(provider)) {
                    request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
                } else if (!"Gemini".equals(provider) && apiKey != null && !apiKey.isBlank()) {
                    request.header("Authorization", "Bearer " + apiKey);
                }
                var response = java.net.http.HttpClient.newHttpClient().send(
                        request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                }
                java.util.List<String> models = parseModelIds(response.body());
                if (models.isEmpty()) throw new IllegalStateException("供应商没有返回模型列表");
                javafx.application.Platform.runLater(() -> {
                    String selected = modelCombo.getValue();
                    modelCombo.getItems().setAll(models);
                    if (selected != null && models.contains(selected)) modelCombo.setValue(selected);
                    else modelCombo.getSelectionModel().selectFirst();
                    finishFetchModels();
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    finishFetchModels();
                    showAlert("获取模型失败", e.getMessage());
                });
            }
            return null;
        });
    }

    private void finishFetchModels() {
        fetchModelsButton.setDisable(false);
        fetchModelsButton.setText("获取模型");
    }

    private static String toModelsEndpoint(String provider, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("请先填写 Base URL");
        String value = endpoint.trim().replaceAll("/+$", "");
        if ("Anthropic".equals(provider)) return value.replaceFirst("/messages$", "/models");
        if ("Ollama".equals(provider)) return value.replaceFirst("/api/generate$", "/api/tags");
        if ("Gemini".equals(provider)) return value.endsWith("/models") ? value : value + "/models";
        value = value.replaceFirst("/chat/completions$", "");
        value = value.replaceFirst("/responses$", "");
        return value.endsWith("/models") ? value : value + "/models";
    }

    private static java.util.List<String> parseModelIds(String json) {
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        com.google.gson.JsonArray data = root.has("data") ? root.getAsJsonArray("data")
                : root.has("models") ? root.getAsJsonArray("models") : new com.google.gson.JsonArray();
        java.util.List<String> result = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : data) {
            com.google.gson.JsonObject object = element.getAsJsonObject();
            if (object.has("id")) result.add(object.get("id").getAsString());
            else if (object.has("name")) result.add(object.get("name").getAsString());
        }
        return result.stream().distinct().sorted().toList();
    }

    private com.runzhi.aishell.ai.AIAdapter createTestAdapter(String apiKey, String endpoint, String model) {
        String provider = providerCombo.getValue();
        return switch (provider == null ? "" : provider) {
            case "Anthropic" -> new com.runzhi.aishell.ai.ClaudeAdapter(apiKey, endpoint, model);
            case "阿里云" -> new com.runzhi.aishell.ai.QwenAdapter(apiKey, endpoint, model);
            case "百度智能云" -> {
                String[] keys = apiKey.split(":", 2);
                if (keys.length != 2) throw new IllegalArgumentException("百度 API Key 请使用 clientId:clientSecret 格式");
                yield new com.runzhi.aishell.ai.ERNIEBotAdapter(keys[0], keys[1], endpoint, model);
            }
            default -> {
                com.runzhi.aishell.ai.OpenAIAdapter adapter = new com.runzhi.aishell.ai.OpenAIAdapter(apiKey, model);
                com.runzhi.aishell.ai.AIConfig config = adapter.getCurrentConfig();
                config.setBaseUrl(normalizeChatEndpoint(endpoint));
                config.setModel(model);
                adapter.updateConfig(config);
                yield adapter;
            }
        };
    }

    private static String normalizeChatEndpoint(String endpoint) {
        String value = endpoint.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.endsWith("/v1") ? value + "/chat/completions" : value;
    }

    private static String normalizeProviderEndpoint(String provider, String endpoint) {
        return "OpenAI".equals(provider) || "自定义端点".equals(provider)
                ? normalizeChatEndpoint(endpoint) : endpoint.trim();
    }

    private static String mapProviderType(String provider) {
        if (provider == null) return "OpenAI";
        return switch (provider) {
            case "Anthropic" -> "Claude";
            case "阿里云" -> "Qwen";
            case "百度智能云" -> "ERNIE Bot";
            case "Gemini", "Ollama", "DeepSeek", "Moonshot", "Mistral" -> provider;
            case "智谱AI" -> "Zhipu";
            default -> "OpenAI";
        };
    }

    private static String mapConfigTypeToProvider(String type) {
        if (type == null) return "OpenAI";
        return switch (type) {
            case "Claude" -> "Anthropic";
            case "Qwen" -> "阿里云";
            case "ERNIE Bot" -> "百度智能云";
            case "Gemini", "Ollama", "DeepSeek", "Moonshot", "Mistral" -> type;
            case "Zhipu" -> "智谱AI";
            case "OpenAI" -> "OpenAI";
            default -> "自定义端点";
        };
    }

    private void applyProviderDefaults(String provider) {
        if (provider == null) return;
        switch (provider) {
            case "OpenAI" -> { apiEndpointField.setText(AppConstants.OPENAI_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_OPENAI_MODEL); }
            case "Anthropic" -> { apiEndpointField.setText(AppConstants.ANTHROPIC_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_CLAUDE_MODEL); }
            case "阿里云" -> { apiEndpointField.setText(AppConstants.QWEN_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_QWEN_MODEL); }
            case "百度智能云" -> { apiEndpointField.setText(AppConstants.ERNIE_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_ERNIE_BOT_MODEL); }
            case "Gemini" -> { apiEndpointField.setText(AppConstants.GEMINI_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_GEMINI_MODEL); }
            case "Ollama" -> { apiEndpointField.setText(AppConstants.OLLAMA_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_OLLAMA_MODEL); }
            case "DeepSeek" -> { apiEndpointField.setText(AppConstants.DEEPSEEK_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_DEEPSEEK_MODEL); }
            case "Moonshot" -> { apiEndpointField.setText(AppConstants.MOONSHOT_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_MOONSHOT_MODEL); }
            case "Mistral" -> { apiEndpointField.setText(AppConstants.MISTRAL_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_MISTRAL_MODEL); }
            case "智谱AI" -> { apiEndpointField.setText(AppConstants.ZHIPU_ENDPOINT); modelCombo.setValue(AppConstants.DEFAULT_ZHIPU_MODEL); }
            default -> { }
        }
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
