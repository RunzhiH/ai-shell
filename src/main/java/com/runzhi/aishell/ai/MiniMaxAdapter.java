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

public class MiniMaxAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private String groupId;
    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "abab5.5-chat";

    public MiniMaxAdapter(String apiKey, String groupId, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
        this.groupId = groupId;
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: MiniMax adapter not configured. Please set API key and group ID.";
        }

        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        body.addProperty("group_id", groupId);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Authorization", "Bearer " + apiKey);
        return executeJsonRequest("MiniMax", () -> HttpClientUtil.doPost(baseUrl, body, headers), this::extractOpenAICompatible);
    }

    @Override
    public boolean isConfigured() {
        return super.isConfigured() && 
               groupId != null && !groupId.trim().isEmpty();
    }

    public void streamQuery(String query, Consumer<String> delta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        body.addProperty("group_id", groupId);
        streamChatCompletions(body, Map.of("Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey), delta, cancelled);
    }
}