/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runzhi.aishell.common.util.HttpClientUtil;

import java.util.HashMap;
import java.util.Map;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class QwenAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    private static final String DEFAULT_MODEL = "qwen-max";

    public QwenAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: Qwen adapter not configured. Please set API key.";
        }

        try {
            // 创建消息对象
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", query);

            // 创建消息数组
            com.google.gson.JsonArray messagesArray = new com.google.gson.JsonArray();
            messagesArray.add(message);
            
            // 创建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            JsonObject input = new JsonObject();
            input.add("messages", messagesArray);
            requestBody.add("input", input);
            
            // 添加参数配置
            JsonObject parameters = new JsonObject();
            parameters.addProperty("temperature", currentTemperature());
            parameters.addProperty("top_p", 0.95);
            parameters.addProperty("max_tokens", currentMaxTokens());
            requestBody.add("parameters", parameters);

            // 设置请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Authorization", "Bearer " + apiKey);

            return executeJsonRequest("Qwen", () -> HttpClientUtil.doPost(baseUrl, requestBody, headers),
                    root -> { String text = extractText(root); return text == null ? "" : text; });
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public void streamQuery(String query, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", query);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);
        JsonObject input = new JsonObject();
        input.add("messages", messages);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("incremental_output", true);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", input);
        body.add("parameters", parameters);
        HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(baseUrl))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey).header("X-DashScope-SSE", "enable")
                .timeout(Duration.ofSeconds(com.runzhi.aishell.common.config.AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        AIStreamHttpClient.stream(httpClient, request, true, this::extractText, onDelta, cancelled);
    }

    private String extractText(JsonObject response) {
        try {
            JsonObject output = response.getAsJsonObject("output");
            if (output == null) return null;
            if (output.has("text")) return output.get("text").getAsString();
            JsonElement choices = output.get("choices");
            if (choices != null && choices.isJsonArray() && choices.getAsJsonArray().size() > 0) {
                JsonElement message = choices.getAsJsonArray().get(0).getAsJsonObject().get("message");
                if (message != null && message.isJsonObject()) {
                    return extractMessageText(message.getAsJsonObject());
                }
            }
            return null;
        } catch (Exception ignored) { return null; }
    }
}
