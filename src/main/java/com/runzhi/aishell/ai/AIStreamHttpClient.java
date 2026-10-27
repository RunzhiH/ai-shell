/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared UTF-8 SSE/JSONL reader for provider-native streaming endpoints. */
final class AIStreamHttpClient {
    private static final Gson GSON = new Gson();

    static void stream(HttpClient client, HttpRequest request, boolean sse,
                       Function<JsonObject, String> extractDelta,
                       Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                String error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("API request failed with status " + response.statusCode() + ": " + error);
            }
        }
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            boolean receivedContent = false;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                String data = sse && line.startsWith("data:") ? line.substring(5).trim() : line.trim();
                if (data.isBlank() || (sse && !line.startsWith("data:"))) continue;
                if ("[DONE]".equals(data)) break;
                // data 为 "null" 或非法 JSON 时 fromJson 返回 null，直接 has() 会 NPE（2026-10-23 审计 P1-8）
                JsonObject event = GSON.fromJson(data, JsonObject.class);
                if (event == null) continue;
                if (event.has("error")) throw new IOException(event.get("error").toString());
                String delta = extractDelta.apply(event);
                if (delta != null && !delta.isEmpty()) {
                    receivedContent = true;
                    onDelta.accept(delta);
                }
            }
            if (!cancelled.getAsBoolean() && !receivedContent) {
                throw new IOException("AI stream completed without content");
            }
        }
    }

    private AIStreamHttpClient() {}
}
