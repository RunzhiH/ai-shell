/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** 默认 AI 配置必须显式记录，临时回退不得覆盖用户选择。 */
class AIConfigManagerDefaultSelectionTest {

    @Test
    void explicitDefaultIsPersistedAndFallbackDoesNotOverwriteIt() {
        AIConfigManager manager = AIConfigManager.getInstance();
        List<AIConfig> configs = manager.getAllConfigs();
        assertTrue(configs.size() >= 2, "测试环境应有至少两个可用配置");

        String originalDefault = manager.getDefaultConfigName();
        String chosen = configs.get(0).getName();
        String fallback = configs.get(1).getName();
        try {
            assertTrue(manager.setDefaultConfig(chosen));
            assertEquals(chosen, manager.getDefaultConfigName());
            assertEquals(chosen, manager.getCurrentConfig().getName(), "显式设置默认应同时切换当前配置");

            Preferences preferences = Preferences.userNodeForPackage(AIConfigManager.class);
            assertEquals(chosen, preferences.get("ai.default.config", null), "默认选择必须写入持久化偏好");

            manager.useFallbackConfig(fallback);
            assertEquals(fallback, manager.getCurrentConfig().getName(), "临时回退只改变当前配置");
            assertEquals(chosen, manager.getDefaultConfigName(), "临时回退不得覆盖已记录的默认选择");
            assertEquals(chosen, preferences.get("ai.default.config", null));
        } finally {
            if (originalDefault != null && manager.getConfigByName(originalDefault) != null) {
                manager.setDefaultConfig(originalDefault);
            }
        }
    }

    @Test
    void agentAdapterRaisesTokenBudgetWithoutChangingSavedConfig() {
        AIConfigManager manager = AIConfigManager.getInstance();
        String original = manager.getCurrentConfig() == null ? null : manager.getCurrentConfig().getName();
        AIConfig agentConfig = new AIConfig("agent-budget-test", "DeepSeek", "key",
                "https://example.invalid", "deepseek-chat");
        agentConfig.setMaxTokens(1024);
        manager.addConfig(agentConfig);
        try {
            manager.setDefaultConfig("agent-budget-test");

            DeepSeekAdapter direct = (DeepSeekAdapter) manager.getCurrentAIAdapter();
            JsonObject directBody = direct.buildRequest("x", false);
            assertEquals(1024, directBody.get("max_tokens").getAsInt(), "普通请求应遵守用户设置");

            DeepSeekAdapter agent = (DeepSeekAdapter) manager.getAgentAIAdapter();
            JsonObject agentBody = agent.buildRequest("x", false);
            assertEquals(AIConfigManager.AGENT_MIN_MAX_TOKENS, agentBody.get("max_tokens").getAsInt(),
                    "Agent 请求必须获得足够的输出预算");
            assertEquals(1024, manager.getConfigByName("agent-budget-test").getMaxTokens(),
                    "Agent 预算不得写回用户配置");
        } finally {
            if (original != null && manager.getConfigByName(original) != null) manager.setDefaultConfig(original);
            manager.removeConfig("agent-budget-test");
        }
    }
}
