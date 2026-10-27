/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.impl;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.ai.AIService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * AI服务实现类
 */
@Slf4j
public class AIServiceImpl implements AIService {

    private AIConfigManager configManager;
    private volatile AIAdapter currentAdapter;

    public AIServiceImpl() {
        this.configManager = AIConfigManager.getInstance();
        this.currentAdapter = configManager.getCurrentAIAdapter();
    }

    @Override
    public String sendQuery(String query) {
        log.debug("Sending AI query to {} ({} chars)", getName(), query == null ? 0 : query.length());
        if (currentAdapter != null && currentAdapter.isConfigured()) {
            String response = currentAdapter.sendQuery(query);
            log.debug("Received AI response ({} chars)", response == null ? 0 : response.length());
            return response;
        }
        log.warn("AI adapter not configured. Please configure your AI service.");
        return "Error: AI adapter not configured. Please configure your AI service.";
    }

    @Override
    public boolean isConfigured() {
        return currentAdapter != null && currentAdapter.isConfigured();
    }

    @Override
    public String getName() {
        if (currentAdapter != null) {
            return currentAdapter.getName();
        }
        return "Unconfigured AI Service";
    }

    @Override
    public void switchToConfig(String configName) {
        configManager.setCurrentConfig(configName);
        this.currentAdapter = configManager.getCurrentAIAdapter();
    }

    @Override
    public AIConfig getCurrentConfig() {
        return configManager.getCurrentConfig();
    }

    @Override
    public void updateAdapter(AIConfig config) {
        configManager.addConfig(config);
        switchToConfig(config.getName());
    }

    @Override
    public Map<String, Object> getServiceStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("configured", isConfigured());
        status.put("serviceName", getName());
        status.put("currentConfig", getCurrentConfig());
        status.put("availableConfigs", configManager.getAllConfigs().size());
        return status;
    }

    @Override
    public boolean testConnection() {
        try {
            String response = sendQuery("测试连接");
            // 仅按约定的错误前缀判定，避免正常回答中出现 "Error" 字样被误判为连接失败（2026-10-23 审计 P1-8）
            return response != null && !response.startsWith("Error:");
        } catch (Exception e) {
            log.error("Error testing AI connection", e);
            return false;
        }
    }

    @Override
    public void reinitializeAdapter() {
        this.currentAdapter = configManager.getCurrentAIAdapter();
    }
    
    @Override
    public String processCommand(String command) {
        log.debug("Processing AI command request ({} chars)", command == null ? 0 : command.length());
        return sendQuery(command);
    }
    
    @Override
    public AIAdapter getAIAdapter() {
        return currentAdapter;
    }
}
