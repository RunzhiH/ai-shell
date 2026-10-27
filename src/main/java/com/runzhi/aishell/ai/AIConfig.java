/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import lombok.Data;

@Data
public class AIConfig {
    private String name;
    private String type; // "OpenAI", "Claude", "Ollama", "Gemini", "Qwen", "ERNIE Bot", "Zhipu", "Moonshot", "MiniMax", "Mistral", "DeepSeek", "Xiaomimimo"
    private String apiKey;
    private String baseUrl;
    private String model;
    private String additionalParams; // JSON格式的额外参数
    private double temperature;
    private int maxTokens;
    private int maxPredictTokens;
    private double topP;


    public AIConfig(String aiName, String type, String apiKey, String url, String model) {
        this.name = aiName;
        this.type = type;
        this.apiKey = apiKey;
        this.baseUrl = url;
        this.model = model;
        this.additionalParams = "";
    }

    public AIConfig() {

    }

    // 自动生成的getter和setter方法
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAdditionalParams() {
        return additionalParams;
    }

    public void setAdditionalParams(String additionalParams) {
        this.additionalParams = additionalParams;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxPredictTokens() {
        return maxPredictTokens;
    }

    public void setMaxPredictTokens(int maxPredictTokens) {
        this.maxPredictTokens = maxPredictTokens;
    }

    public double getTopP() {
        return topP;
    }

    public void setTopP(double topP) {
        this.topP = topP;
    }
}