/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.util.HttpClientUtil;

import java.util.Map;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class OllamaAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    
    // 添加常量
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_BASE_URL = "http://localhost:11434/api/generate";
    private static final String DEFAULT_MODEL = AppConstants.DEFAULT_OLLAMA_MODEL;
    private static final int MAX_PREDICT_TOKENS = AppConstants.DEFAULT_MAX_PREDICT_TOKENS;

    public OllamaAdapter(String baseUrl, String model) {
        super(null, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL); // Ollama不需要API密钥
    }

    private JsonObject createOptionsObject() {
        JsonObject options = new JsonObject();
        options.addProperty("temperature", currentTemperature());
        options.addProperty("top_p", AppConstants.DEFAULT_TOP_P);
        options.addProperty("num_predict", MAX_PREDICT_TOKENS);
        return options;
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: Ollama adapter not configured. Please check server connection.";
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", query);
        body.addProperty("stream", false);
        body.add("options", createOptionsObject());
        return executeJsonRequest("Ollama", () -> HttpClientUtil.doPost(baseUrl, body,
                Map.of("Content-Type", "application/json")), root -> jsonString(root.get("response")));
    }

    @Override
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
    }
    
    @Override
    public void setConfig(Map<String, Object> config) {
        if (config != null) {
            if (config.containsKey("model")) {
                this.model = (String) config.get("model");
            }
            if (config.containsKey("baseUrl")) {
                this.baseUrl = (String) config.get("baseUrl");
            }
        }
    }

    @Override
    public void streamQuery(String query, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", query);
        body.addProperty("stream", true);
        body.add("options", createOptionsObject());
        HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        AIStreamHttpClient.stream(httpClient, request, false, event ->
                event.has("response") ? event.get("response").getAsString() : null,
                onDelta, cancelled);
    }
}