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
public class GeminiAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-pro";

    public GeminiAdapter(String apiKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        log.debug("Sending Gemini query ({} chars)", query == null ? 0 : query.length());
        if (!isConfigured()) {
            log.warn("AI adapter not configured. Please set API key.");
            return "Error: Gemini adapter not configured. Please set API key.";
        }

        try {
            // 创建parts对象
            JsonObject partsObject = new JsonObject();
            partsObject.addProperty("text", query);

            // 创建内容对象
            JsonObject contentObject = new JsonObject();
            contentObject.addProperty("role", "user");
            
            // 创建parts数组
            com.google.gson.JsonArray partsArray = new com.google.gson.JsonArray();
            partsArray.add(partsObject);
            contentObject.add("parts", partsArray);

            // 创建请求体
            JsonObject requestBody = new JsonObject();
            
            // 创建contents数组
            com.google.gson.JsonArray contentsArray = new com.google.gson.JsonArray();
            contentsArray.add(contentObject);
            requestBody.add("contents", contentsArray);
            
            // 添加生成参数
            JsonObject generationConfig = new JsonObject();
            generationConfig.addProperty("temperature", currentTemperature());
            generationConfig.addProperty("topP", AppConstants.DEFAULT_TOP_P);
            generationConfig.addProperty("maxOutputTokens", AppConstants.DEFAULT_MAX_TOKENS);
            requestBody.add("generationConfig", generationConfig);

            // 设置请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            // 构造完整的请求URL
            String url = baseUrl + model + ":generateContent?key=" + apiKey;

            return executeJsonRequest("Gemini", () -> HttpClientUtil.doPost(url, requestBody, headers), root -> {
                if (root.has("promptFeedback") && root.getAsJsonObject("promptFeedback").has("blockReason")) {
                    throw new IllegalStateException("request blocked: "
                            + jsonString(root.getAsJsonObject("promptFeedback").get("blockReason")));
                }
                if (root.has("candidates") && !root.getAsJsonArray("candidates").isEmpty()
                        && "MAX_TOKENS".equals(jsonString(root.getAsJsonArray("candidates").get(0)
                        .getAsJsonObject().get("finishReason")))) {
                    throw new IllegalStateException("output truncated (finishReason=MAX_TOKENS)");
                }
                String result = extractGeminiText(root);
                return result == null ? "" : result;
            });
        } catch (Exception e) {
            log.error("Exception in AI query: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    private String extractGeminiText(JsonObject jsonResponse) {
        try {
            if (!jsonResponse.has("candidates") || jsonResponse.getAsJsonArray("candidates").size() == 0) return null;
            StringBuilder text = new StringBuilder();
            for (JsonElement candidate : jsonResponse.getAsJsonArray("candidates")) {
                if (candidate == null || !candidate.isJsonObject()) continue;
                JsonObject content = candidate.getAsJsonObject().getAsJsonObject("content");
                if (content == null || !content.has("parts")) continue;
                for (JsonElement part : content.getAsJsonArray("parts")) {
                    if (part != null && part.isJsonObject()) {
                        JsonElement partText = part.getAsJsonObject().get("text");
                        if (partText != null && !partText.isJsonNull() && partText.isJsonPrimitive()) {
                            text.append(partText.getAsString());
                        }
                    }
                }
            }
            return text.length() == 0 ? null : text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void streamQuery(String query, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        JsonObject part = new JsonObject();
        part.addProperty("text", query);
        JsonObject content = new JsonObject();
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        parts.add(part);
        content.add("parts", parts);
        com.google.gson.JsonArray contents = new com.google.gson.JsonArray();
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);
        String url = baseUrl + model + ":streamGenerateContent?alt=sse&key=" +
                java.net.URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        AIStreamHttpClient.stream(httpClient, request, true, event -> {
            try { return event.getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString(); }
            catch (Exception ignored) { return null; }
        }, onDelta, cancelled);
    }
}
