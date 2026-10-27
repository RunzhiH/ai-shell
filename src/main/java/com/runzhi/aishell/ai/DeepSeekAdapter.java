/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.util.HttpClientUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class DeepSeekAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    public DeepSeekAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: DeepSeek adapter not configured. Please set API key.";
        }

        // 不再强制 response_format=json_object：提示词不含 JSON 字样时 DeepSeek 会直接 400，
        // 且流式路径从未设置该参数——两路径行为保持一致（2026-10-23 审计 P1-8）
        JsonObject requestBody = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
            requestBody.addProperty("stream", false);
            if (model != null && model.startsWith("deepseek-v4")) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "disabled");
                requestBody.add("thinking", thinking);
            }

            // 设置请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Authorization", "Bearer " + apiKey);

            // 发送请求
        return executeJsonRequest("DeepSeek", () -> HttpClientUtil.doPost(baseUrl, requestBody, headers),
                this::extractOpenAICompatible);
    }

    private String extractContentOnly(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString().trim();
        if (!content.isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                JsonElement partText = part.getAsJsonObject().get("text");
                if (partText != null && !partText.isJsonNull()) text.append(partText.getAsString());
            }
        }
        return text.toString().trim();
    }

    private String stringValue(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    public void streamQuery(String query, Consumer<String> delta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        streamChatCompletions(createMessageRequestBody(query, currentTemperature(), currentMaxTokens()),
                Map.of("Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey), delta, cancelled);
    }
}