/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.util.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Slf4j
public class OpenAIAdapter extends BaseAIAdapter implements StreamingAIAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = AppConstants.DEFAULT_OPENAI_MODEL;

    public OpenAIAdapter(String apiKey) {
        super(apiKey, DEFAULT_BASE_URL, AppConstants.DEFAULT_OPENAI_MODEL, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    public OpenAIAdapter(String apiKey, String model) {
        super(apiKey, DEFAULT_BASE_URL, model, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    @Override
    public String sendQuery(String query) {
        log.debug("Sending OpenAI query ({} chars)", query == null ? 0 : query.length());
        if (!isConfigured()) {
            log.warn("AI adapter not configured. Please set API key.");
            return "Error: AI adapter not configured. Please set API key.";
        }

        JsonObject body = createMessageRequestBody(query, currentTemperature(), currentMaxTokens());
        body.addProperty("top_p", AppConstants.DEFAULT_TOP_P);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Authorization", "Bearer " + apiKey);
        return executeJsonRequest("OpenAI", () -> HttpClientUtil.doPost(baseUrl, body, headers),
                this::extractOpenAICompatible);

    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @Override
    public void streamQuery(String query, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("AI adapter not configured. Please set API key.");
        JsonObject requestBody = createMessageRequestBody(query, AppConstants.DEFAULT_TEMPERATURE,
                AppConstants.DEFAULT_MAX_TOKENS);
        requestBody.addProperty("stream", true);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(AppConstants.HTTP_REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                String error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("API request failed with status " + response.statusCode() + ": " + error);
            }
        }
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isBlank()) continue;
                JsonObject event = gson.fromJson(data, JsonObject.class);
                if (event.has("error")) {
                    throw new IllegalStateException(event.get("error").toString());
                }
                if (!event.has("choices") || event.getAsJsonArray("choices").isEmpty()) continue;
                JsonObject choice = event.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (!choice.has("delta")) continue;
                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    onDelta.accept(delta.get("content").getAsString());
                }
            }
        }
    }

}