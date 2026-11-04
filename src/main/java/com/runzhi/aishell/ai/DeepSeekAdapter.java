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
import java.util.Locale;
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
        JsonObject requestBody = buildRequest(query, false);

        // 设置请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);

        // 发送请求
        return executeJsonRequest("DeepSeek", () -> HttpClientUtil.doPost(baseUrl, requestBody, headers),
                this::extractOpenAICompatible);
    }

    /**
     * 同步与流式共用的请求体：仅对已知支持 thinking 开关的 DeepSeek 模型关闭思考，
     * 保留用户配置的 temperature/top_p/max_tokens 和 baseUrl。
     * 包级可见，供无网络测试验证实际序列化的 JSON。
     */
    JsonObject buildRequest(String query, boolean stream) {
        JsonObject requestBody = createMessageRequestBody(query, currentTemperature(), effectiveMaxTokens());
        requestBody.addProperty("stream", stream);
        if (supportsThinkingToggle()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            requestBody.add("thinking", thinking);
        }
        return requestBody;
    }

    /**
     * deepseek-flash 与 deepseek-v4 及其带版本后缀的模型支持 thinking.disabled；
     * 对 deepseek-reasoner、deepseek-chat 和未知模型不注入可能不被接受的参数。
     * 使用明确的边界，避免把 deepseek-v40 之类误判成 deepseek-v4。
     */
    private boolean supportsThinkingToggle() {
        if (model == null) return false;
        String value = model.trim().toLowerCase(Locale.ROOT);
        return value.equals("deepseek-flash")
                || value.matches("deepseek-v4(?:[-._].*)?");
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
        streamChatCompletions(buildRequest(query, true),
                Map.of("Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey), delta, cancelled);
    }
}