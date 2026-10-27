/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.ai;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.ai.AIService;
import com.runzhi.aishell.utils.ShellUtils;
import javafx.application.Platform;
import javafx.scene.text.TextFlow;

import java.util.concurrent.ExecutorService;

import com.runzhi.aishell.common.util.ThreadPoolManager;

/**
 * AI助手服务类，提供通用的AI功能
 */
public class AIHelperService {
    
    private final AIService aiService;
    private AIAdapter aiAdapter;
    private static final ExecutorService executorService = ThreadPoolManager.getInstance().getCachedThreadPool();
    
    public AIHelperService() {
        this.aiService = AIShellModuleManager.getInstance().getAiService();
        initializeAIAdapter();
    }
    
    /**
     * 初始化AI适配器
     */
    private void initializeAIAdapter() {
        aiAdapter = aiService.isConfigured() ?
                AIConfigManager.getInstance().getCurrentAIAdapter() : null;
        
        if (aiAdapter == null) {
            // 如果没有配置，尝试使用默认配置
            AIConfigManager.getInstance().setCurrentConfig("默认OpenAI");
            aiAdapter = AIConfigManager.getInstance().getCurrentAIAdapter();
        }
    }
    
    /**
     * 在TextFlow中追加输出文本
     *
     * @param textFlow TextFlow控件
     * @param text 要追加的文本
     * @param color 文本颜色
     */
    public void appendToTextFlow(TextFlow textFlow, String text, String color) {
        ShellUtils.appendColoredTextToFlow(textFlow, text, color);
    }
    
    /**
     * 处理AI命令
     *
     * @param command 命令文本
     * @param onResponse 响应处理器
     */
    public void handleAICommand(String command, AIResponseHandler onResponse) {
        if (aiAdapter != null) {
            // 在后台线程池中处理AI响应
            executorService.submit(() -> {
                try {
                    String response = aiAdapter.sendQuery(command);
                    Platform.runLater(() -> onResponse.onSuccess(response));
                } catch (Exception e) {
                    Platform.runLater(() -> onResponse.onError(e));
                }
            });
        } else {
            Platform.runLater(() -> onResponse.onError(new Exception("未配置AI服务，请先配置AI服务。")));
        }
    }
    
    /**
     * 检查是否为AI命令
     *
     * @param command 命令文本
     * @return 是否为AI命令
     */
    public boolean isAICommand(String command) {
        // 简单的AI命令检测（可以根据需要扩展）
        // 可以使用特定前缀，如"ai:" 或 "@ai" 等
        return command.startsWith("ai:") ||
                command.startsWith("@ai") ||
                command.startsWith("ask ") ||
                command.startsWith("explain ");
    }
    
    /**
     * 提取AI命令内容（去除前缀）
     *
     * @param command 命令文本
     * @return AI命令内容
     */
    public String extractAICommandContent(String command) {
        if (command.startsWith("ai:")) {
            return command.substring(3).trim(); // 移除"ai:"前缀
        } else if (command.startsWith("@ai")) {
            return command.substring(3).trim(); // 移除"@ai"前缀
        } else if (command.startsWith("ask ")) {
            return command.substring(4).trim(); // 移除"ask "前缀
        } else if (command.startsWith("explain ")) {
            return command.substring(8).trim(); // 移除"explain "前缀
        }
        return command; // 如果没有匹配的前缀，返回原始命令
    }
    
    /**
     * AI响应处理器接口
     */
    @FunctionalInterface
    public interface AIResponseHandler {
        void onSuccess(String response);
        
        default void onError(Exception e) {
            // 默认错误处理
        }
    }
    
    public AIAdapter getAiAdapter() {
        return aiAdapter;
    }
    
    public AIService getAiService() {
        return aiService;
    }
    
    /**
     * 根据用户需求生成命令
     */
    public static String generateCommand(String userInput) {
        // 这里可以实现根据用户输入生成相应命令的逻辑
        // 暂时返回原输入，实际实现可能涉及AI处理
        return userInput;
    }
    
    /**
     * 关闭AIHelperService，释放资源
     */
    public void shutdown() {
        executorService.shutdown();
    }
}