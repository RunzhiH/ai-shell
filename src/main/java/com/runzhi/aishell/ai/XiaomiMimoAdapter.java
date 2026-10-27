/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.util.HttpClientUtil;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class XiaomiMimoAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "mimo-v2-flash";

    public XiaomiMimoAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: Xiaomimimo adapter not configured. Please set API key.";
        }

        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        body.addProperty("stream", false);
        body.addProperty("frequency_penalty", 0);
        body.addProperty("presence_penalty", 0);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "api-key", apiKey);
        return executeJsonRequest("Xiaomi Mimo", () -> HttpClientUtil.doPost(baseUrl, body, headers), this::extractOpenAICompatible);
    }

    public void streamQuery(String query, Consumer<String> delta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        streamChatCompletions(createMessageRequestBody(query, currentTemperature(), currentMaxTokens()),
                Map.of("Content-Type", "application/json", "api-key", apiKey), delta, cancelled);
    }
}