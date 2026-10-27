/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.util.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
@Slf4j
public class ZhipuAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-4";

    public ZhipuAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    private String generateAuthorization() {
        // 智谱AI使用APIKey:Secret格式的Basic认证
        String credentials = apiKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    @Override
    public String sendQuery(String query) {
        log.debug("Sending Zhipu query ({} chars)", query == null ? 0 : query.length());
        if (!isConfigured()) {
            log.warn("AI adapter not configured. Please set API key.");
            return "Error: Zhipu adapter not configured. Please set API key.";
        }

        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Authorization", generateAuthorization());
        return executeJsonRequest("Zhipu", () -> HttpClientUtil.doPost(baseUrl, body, headers), this::extractOpenAICompatible);
    }

    public void streamQuery(String query, Consumer<String> delta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        streamChatCompletions(createMessageRequestBody(query, currentTemperature(), currentMaxTokens()),
                Map.of("Content-Type", "application/json", "Authorization", generateAuthorization()),
                delta, cancelled);
    }
}