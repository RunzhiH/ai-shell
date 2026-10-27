/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runzhi.aishell.common.config.AppConstants;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;

/**
 * AI适配器基类，提供通用的AI服务实现
 * 
 * 该基类封装了AI服务的通用功能，包括消息体创建、HTTP请求构建、
 * 配置检查等。通过继承此类，具体的AI适配器可以减少重复代码，
 * 并保持统一的接口实现。
 * 
 * @author AIShell Team
 * @version 1.0
 * @since 2024
 */
@Slf4j
public abstract class BaseAIAdapter implements AIAdapter {
    protected String apiKey;
    protected String baseUrl;
    protected String model;
    /** 最近一次 updateConfig 写入的运行时配置；适配器构造请求时用它读取用户在设置页配置的参数。 */
    protected AIConfig runtimeConfig;
    protected Gson gson = new Gson();
    protected final HttpClient httpClient;

    /** 用户配置的温度（未配置或非法时回退默认值）：设置页参数由此生效（2026-10-23 审计 P1-8）。 */
    protected double currentTemperature() {
        double value = runtimeConfig != null ? runtimeConfig.getTemperature() : 0;
        return value > 0 ? value : AppConstants.DEFAULT_TEMPERATURE;
    }

    /** 用户配置的最大 token 数（未配置时回退默认值）。 */
    protected int currentMaxTokens() {
        int value = runtimeConfig != null ? runtimeConfig.getMaxTokens() : 0;
        return value > 0 ? value : AppConstants.DEFAULT_MAX_TOKENS;
    }

    /** 用户配置的 top_p（未配置时回退默认值）。 */
    protected double currentTopP() {
        double value = runtimeConfig != null ? runtimeConfig.getTopP() : 0;
        return value > 0 ? value : AppConstants.DEFAULT_TOP_P;
    }

    public BaseAIAdapter(String apiKey, String baseUrl, String model, String defaultBaseUrl, String defaultModel) {
        this.apiKey = apiKey;
        this.baseUrl = sanitizeBaseUrl(baseUrl != null ? baseUrl : defaultBaseUrl);
        this.model = model != null ? model : defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String sanitizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String lower = url.strip().toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            throw new IllegalArgumentException("AI service URL must use http or https protocol: " + url);
        }
        return url.strip();
    }

    /**
     * 创建带有通用消息结构的请求体
     * 
     * 构建标准的AI服务请求体结构，包含模型信息、用户消息、温度参数、
     * 最大令牌数和top_p参数等。该方法为所有AI适配器提供统一的消息
     * 体创建功能。
     * 
     * @param query 用户输入的查询内容
     * @param temperature 控制生成随机性的温度参数
     * @param maxTokens 最大生成令牌数限制
     * @return 包含标准消息结构的JsonObject
     * @since 1.0
     */
    protected JsonObject createMessageRequestBody(String query, double temperature, int maxTokens) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        
        // 创建消息数组
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", query);
        
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);
        requestBody.add("messages", messages);
        
        // 添加通用参数：优先使用用户在设置页配置的值，形参仅作回退（2026-10-23 审计 P1-8）
        requestBody.addProperty("temperature", temperature > 0 ? temperature : currentTemperature());
        requestBody.addProperty("max_tokens", maxTokens > 0 ? maxTokens : currentMaxTokens());
        requestBody.addProperty("top_p", currentTopP());

        return requestBody;
    }

    /**
     * 创建HTTP请求构建器
     * 
     * 构建标准的HTTP POST请求构建器，设置请求URL、内容类型头部和
     * 请求超时时间。该方法为所有AI适配器提供统一的HTTP请求
     * 构建功能。
     * 
     * @param requestBodyStr JSON格式的请求体字符串
     * @return 配置好的HTTP请求构建器
     * @since 1.0
     */
    protected HttpRequest.Builder createHttpRequestBuilder(String requestBodyStr) {
        return HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .timeout(Duration.ofSeconds(30));
    }

    protected void streamChatCompletions(JsonObject body, Map<String, String> headers,
                                         Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception {
        body.addProperty("stream", true);
        HttpRequest.Builder builder = HttpRequest.newBuilder(java.net.URI.create(baseUrl))
                .header("Accept", "text/event-stream")
                // 流式响应不能设置覆盖整个响应体的 request timeout：SSE 长暂停（推理模型 thinking、慢网络）
                // 超过 30s 即被中断。连接超时已由 HttpClient 的 connectTimeout(10s) 覆盖（2026-10-23 审计 P1-8）
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        AIStreamHttpClient.stream(httpClient, builder.build(), true, event -> {
            try {
                JsonObject delta = event.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("delta");
                return delta != null && delta.has("content") && !delta.get("content").isJsonNull()
                        ? delta.get("content").getAsString() : null;
            } catch (Exception ignored) { return null; }
        }, onDelta, cancelled);
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName().replace("Adapter", "");
    }

    /**
     * 从 OpenAI 兼容的 message 对象中抽取文本内容，兼容常见非标准返回：
     * <ul>
     *   <li>content 为字符串（标准格式）；</li>
     *   <li>content 为文本块数组（新版 OpenAI 兼容格式）；</li>
     *   <li>content 缺失/为空时回退到 reasoning_content（DeepSeek reasoner、OpenAI o 系列等推理模型）；</li>
     *   <li>content 为 JSON null 时按空处理（避免 Gson 的 JsonNull 被转成字面量 "null"）。</li>
     * </ul>
     *
     * @param message 解析后的 assistant message 对象
     * @return 抽取到的文本，可能为空字符串
     */
    protected String extractMessageText(JsonObject message) {
        String content = extractContentField(message);
        return content;
    }

    /** Unified non-streaming response pipeline used by every provider adapter. */
    protected String executeJsonRequest(String provider, Supplier<String> request,
                                        Function<JsonObject, String> extractor) {
        String problem = "empty response";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String raw = request.get();
                if (raw == null || raw.isBlank()) problem = "HTTP response body is empty";
                else if (raw.startsWith("Error:")) problem = raw.substring(6).trim();
                else {
                    JsonObject root = gson.fromJson(raw, JsonObject.class);
                    String apiError = extractApiError(root);
                    if (!apiError.isBlank()) problem = apiError;
                    else {
                        String text = extractor.apply(root);
                        if (text != null && !text.isBlank()) return text.trim();
                        problem = "response contains no final content";
                    }
                }
            } catch (RuntimeException e) {
                problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (attempt < 3) {
                try { Thread.sleep(300L * attempt); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Error: " + provider + " request interrupted";
                }
            }
        }
        return "Error: " + provider + " returned no usable content after 3 attempts: " + problem;
    }

    protected String extractOpenAICompatible(JsonObject root) {
        JsonArray choices = root == null ? null : root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("response contains no choices");
        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = jsonString(choice.get("finish_reason"));
        if ("length".equals(finish)) throw new IllegalStateException("output truncated (finish_reason=length)");
        String content = extractMessageText(choice.getAsJsonObject("message"));
        if (!content.isBlank()) return content;
        JsonObject message = choice.getAsJsonObject("message");
        String reasoning = message == null ? "" : jsonString(message.get("reasoning_content"));
        if (!reasoning.isBlank()) throw new IllegalStateException("only reasoning content was returned; final content is empty");
        return "";
    }

    protected String jsonString(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private String extractApiError(JsonObject root) {
        if (root == null) return "response is not a JSON object";
        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) {
            if (error.isJsonPrimitive()) return error.getAsString();
            if (error.isJsonObject()) {
                JsonObject object = error.getAsJsonObject();
                for (String key : new String[]{"message", "msg", "detail", "code"}) {
                    String value = jsonString(object.get(key));
                    if (!value.isBlank()) return value;
                }
            }
            return error.toString();
        }
        for (String key : new String[]{"error_msg", "error_message"}) {
            String value = jsonString(root.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String extractContentField(JsonObject message) {
        if (message == null) return "";
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part != null && part.isJsonObject()) {
                    JsonElement partText = part.getAsJsonObject().get("text");
                    if (partText != null && !partText.isJsonNull() && partText.isJsonPrimitive()) {
                        text.append(partText.getAsString());
                    }
                }
            }
            return text.toString();
        }
        return "";
    }
    
    @Override
    public String generateCommand(String naturalLanguage) {
        // 默认实现：将自然语言描述转换为命令
        String prompt = "请将以下自然语言描述转换为具体的Linux/Unix命令：" + naturalLanguage + 
                     "\n只需要返回命令本身，不要添加任何解释或说明。";
        return sendQuery(prompt);
    }
    
    @Override
    public String explainCommand(String command) {
        // 默认实现：解释给定命令的作用
        String prompt = "请解释以下命令的作用：" + command + 
                     "\n请简明扼要地说明该命令的功能和用途。";
        return sendQuery(prompt);
    }
    
    @Override
    public String getHelp(String topic) {
        // 默认实现：提供关于特定主题的帮助
        String prompt = "请提供关于'" + topic + "'的帮助信息。" + 
                     "请简明扼要地解释相关概念和使用方法。";
        return sendQuery(prompt);
    }
    
    /**
     * 获取当前配置信息
     */
    public AIConfig getCurrentConfig() {
        AIConfig config = new AIConfig();
        config.setApiKey(this.apiKey);
        config.setBaseUrl(this.baseUrl);
        config.setModel(this.model);
        config.setName(getName());
        return config;
    }
    
    /**
     * 更新配置
     */
    public void updateConfig(AIConfig config) {
        this.apiKey = config.getApiKey();
        // 复用构造器的 http/https 校验，绕过校验的非法 URL 会在此抛 IllegalArgumentException（2026-10-23 审计 P1-22）
        String url = config.getBaseUrl();
        this.baseUrl = (url == null || url.isBlank()) ? this.baseUrl : sanitizeBaseUrl(url);
        this.model = config.getModel();
        this.runtimeConfig = config;
    }
    
    /**
     * 检查API密钥是否有效
     */
    public boolean validateApiKey() {
        return apiKey != null && apiKey.trim().length() > 5;
    }
    
    /**
     * 获取默认配置
     */
    public AIConfig getDefaultConfig() {
        AIConfig config = new AIConfig();
        config.setApiKey("");
        config.setBaseUrl(this.baseUrl);
        config.setModel(this.model);
        config.setName(getName() + " Default");
        config.setTemperature(AppConstants.DEFAULT_TEMPERATURE);
        config.setMaxTokens(AppConstants.DEFAULT_MAX_TOKENS);
        config.setMaxPredictTokens(AppConstants.DEFAULT_MAX_PREDICT_TOKENS);
        config.setTopP(AppConstants.DEFAULT_TOP_P);
        return config;
    }
}
