/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.util.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
@Slf4j
public class ClaudeAdapter extends BaseAIAdapter implements StreamingAIAdapter {

    // 添加常量
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = AppConstants.DEFAULT_CLAUDE_MODEL;
    private static final int MAX_TOKENS = AppConstants.DEFAULT_MAX_TOKENS;

    public ClaudeAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        log.debug("Sending Claude query ({} chars)", query == null ? 0 : query.length());
        if (!isConfigured()) {
            log.warn("AI adapter not configured. Please set API key.");
            return "Error: Claude adapter not configured. Please set API key.";
        }

        try {
            // 创建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.addProperty("max_tokens", MAX_TOKENS);
            requestBody.addProperty("temperature", AppConstants.DEFAULT_TEMPERATURE);
            requestBody.addProperty("top_p", AppConstants.DEFAULT_TOP_P);

            // 创建消息数组
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", query);

            com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
            messages.add(message);
            requestBody.add("messages", messages);

            // 设置请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");

            return executeJsonRequest("Claude", () -> HttpClientUtil.doPost(baseUrl, requestBody, headers), root -> {
                if ("max_tokens".equals(jsonString(root.get("stop_reason")))) {
                    throw new IllegalStateException("output truncated (stop_reason=max_tokens)");
                }
                if (!root.has("content") || !root.get("content").isJsonArray()) return "";
                StringBuilder text = new StringBuilder();
                for (JsonElement part : root.getAsJsonArray("content")) {
                    if (part.isJsonObject() && "text".equals(jsonString(part.getAsJsonObject().get("type")))) {
                        JsonElement value = part.getAsJsonObject().get("text");
                        if (value != null && !value.isJsonNull()) text.append(value.getAsString());
                    }
                }
                return text.toString();
            });
        } catch (Exception e) {
            log.error("Exception in AI query: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public void setConfig(Map<String, Object> config) {
        if (config != null) {
            if (config.containsKey("apiKey")) {
                this.apiKey = (String) config.get("apiKey");
            }
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
        body.addProperty("max_tokens", Math.min(MAX_TOKENS, currentMaxTokens()));
        body.addProperty("temperature", currentTemperature());
        body.addProperty("stream", true);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", query);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);
        body.add("messages", messages);
        HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        AIStreamHttpClient.stream(httpClient, request, true, event -> {
            if (!"content_block_delta".equals(event.has("type") ? event.get("type").getAsString() : "")) return null;
            JsonObject delta = event.getAsJsonObject("delta");
            return delta != null && delta.has("text") ? delta.get("text").getAsString() : null;
        }, onDelta, cancelled);
    }
}