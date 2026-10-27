/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.util.HttpClientUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ERNIEBotAdapter extends BaseAIAdapter implements StreamingAIAdapter {
    private String secretKey;
    private String accessToken;
    private long tokenExpireTime = 0;
    private static final String DEFAULT_BASE_URL = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/";
    private static final String DEFAULT_MODEL = "ernie-bot-4.5";

    public ERNIEBotAdapter(String apiKey, String secretKey, String baseUrl, String model) {
        super(apiKey, baseUrl, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
        this.secretKey = secretKey;
    }

    /**
     * 获取访问令牌：线程安全的单次刷新（此前无同步，并发请求会重复换 token、
     * 并可能把刚换好的 token 覆盖回过期值）（2026-10-23 审计 P1-10）
     */
    private synchronized String getAccessToken() throws IOException, InterruptedException {
        // 二次检查：等到锁的线程可能已经看到其他线程换好的新 token
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime - 60000) { // 提前1分钟刷新
            return accessToken;
        }

        // 获取新的访问令牌
        String url = "https://aip.baidubce.com/oauth/2.0/token?" +
                "grant_type=client_credentials&" +
                "client_id=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) + "&" +
                "client_secret=" + URLEncoder.encode(secretKey, StandardCharsets.UTF_8);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        String response = HttpClientUtil.doGet(url, headers);

        if (response.startsWith("Error:")) {
            throw new IOException(response);
        }

        JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
        if (jsonResponse == null || !jsonResponse.has("access_token")) {
            throw new IOException("ERNIE Bot token 响应缺少 access_token: " + response);
        }
        this.accessToken = jsonResponse.get("access_token").getAsString();
        long expiresIn = jsonResponse.get("expires_in").getAsLong();
        this.tokenExpireTime = System.currentTimeMillis() + (expiresIn * 1000);
        return this.accessToken;
    }

    @Override
    public String sendQuery(String query) {
        if (!isConfigured()) {
            return "Error: ERNIE Bot adapter not configured. Please set API key and secret key.";
        }

        try {
            // 创建请求体：用户在设置页配置的参数（2026-10-23 审计 P1-8）
            JsonObject requestBody = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
            requestBody.addProperty("max_output_tokens", currentMaxTokens());

            // 设置请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            // 首次请求；401(令牌过期/失效)时强制刷新令牌重试一次（此前令牌提前失效只能等下次自然过期）
            String result = queryWithToken(requestBody, headers, false);
            if (result != null && result.startsWith("Error:") && result.contains("401")) {
                invalidateToken();
                result = queryWithToken(requestBody, headers, true);
            }
            return result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String queryWithToken(JsonObject requestBody, Map<String, String> headers, boolean isRetry) throws Exception {
        String token = getAccessToken();
        String url = baseUrl + model + "?access_token=" + token;
        return executeJsonRequest("ERNIE Bot" + (isRetry ? " (token 已刷新重试)" : ""),
                () -> HttpClientUtil.doPost(url, requestBody, headers),
                root -> {
                    if (root.has("is_truncated") && root.get("is_truncated").getAsBoolean())
                        throw new IllegalStateException("output was truncated");
                    return jsonString(root.get("result"));
                });
    }

    /** 强制下次请求重新换取令牌。 */
    private synchronized void invalidateToken() {
        this.accessToken = null;
        this.tokenExpireTime = 0;
    }

    @Override
    public boolean isConfigured() {
        return super.isConfigured() && 
               secretKey != null && !secretKey.trim().isEmpty();
    }

    @Override
    public void streamQuery(String query, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        // 与非流式 sendQuery 一致：未配置时直接拒绝，避免发出 “Authorization: Bearer null”（2026-10-23 审计 P1-8）
        if (!isConfigured()) throw new IllegalStateException(getName() + " 未配置 API Key，无法发起流式请求");
        String token = getAccessToken();
        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        body.addProperty("stream", true);
        String url = baseUrl + model + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        AIStreamHttpClient.stream(httpClient, request, true, event ->
                event.has("result") && event.get("result").isJsonPrimitive()
                        ? event.get("result").getAsString() : null,
                onDelta, cancelled);
    }
}
