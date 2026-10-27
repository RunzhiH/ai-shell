/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.util.HttpClientUtil;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class MistralAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1/chat/completions";
    private static final String DEFAULT_MODEL = "mistral-small-latest";

    public MistralAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: Mistral adapter not configured. Please set API key.";
        }

        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Authorization", "Bearer " + apiKey);
        return executeJsonRequest("Mistral", () -> HttpClientUtil.doPost(baseUrl, body, headers), this::extractOpenAICompatible);
    }

    public void streamQuery(String query, Consumer<String> delta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        streamChatCompletions(createMessageRequestBody(query, AppConstants.DEFAULT_TEMPERATURE,
                AppConstants.DEFAULT_MAX_TOKENS), Map.of("Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey), delta, cancelled);
    }
}