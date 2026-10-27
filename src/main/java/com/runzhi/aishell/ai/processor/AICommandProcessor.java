/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.processor;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.common.ai.AIHelperService;
import com.runzhi.aishell.agent.AgentRuntime;
import com.runzhi.aishell.agent.AgentToolRegistry;
import com.runzhi.aishell.agent.AgentDecision;
import com.runzhi.aishell.agent.SshAgentSessionTool;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * AI命令处理器 - 专门处理AI相关的命令逻辑
 */
@Slf4j
public class AICommandProcessor {
    private static final ExecutorService AI_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-command-request");
        thread.setDaemon(true);
        return thread;
    });
    
    private AIAdapter aiAdapter;
    private AIHelperService aiHelperService;
    
    public AICommandProcessor() {
        initializeAIComponents();
    }
    
    /**
     * 初始化AI组件
     */
    private void initializeAIComponents() {
        this.aiHelperService = new AIHelperService();

        // 配置管理器是运行时配置的唯一事实来源。AIService 可能是在设置保存前创建的，
        // 不能用它缓存的 isConfigured 状态决定是否读取当前配置，更不能擅自切回默认配置。
        this.aiAdapter = AIConfigManager.getInstance().getCurrentAIAdapter();
    }
    
    private Future<?> submitCommandRequest(String sessionId, String command, BiConsumer<String, Boolean> callback,
                                           Consumer<String> feedback) {
        return AI_EXECUTOR.submit(() -> {
            try {
                AIAdapter configured = AIConfigManager.getInstance().getCurrentAIAdapter();
                aiAdapter = configured;
                if (aiAdapter != null) {
                    var sshService = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getSshService();
                    if (sshService == null) throw new IllegalStateException("SSH service is not available");
                    AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(sshService));
                    String aiGeneratedCommand = runtime.resolveCommand(aiAdapter, sessionId, command,
                            message -> Platform.runLater(() -> feedback.accept(message)));
                    if (Thread.currentThread().isInterrupted()) return;
                    if (aiGeneratedCommand != null && !aiGeneratedCommand.isEmpty()) {
                        boolean isCdCommand = isCdCommand(aiGeneratedCommand);
                        Platform.runLater(() -> callback.accept(aiGeneratedCommand, isCdCommand));
                    } else {
                        Platform.runLater(() -> feedback.accept("AI未能生成有效的命令"));
                    }
                } else {
                    Platform.runLater(() -> feedback.accept("AI服务未配置"));
                }
            } catch (Exception e) {
                log.error("处理AI直接命令时出错: {}", e.getMessage(), e);
                if (!Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() -> feedback.accept("处理AI命令时出错: " + e.getMessage()));
                }
            }
        });
    }
    
    public Future<?> processAgentCommand(String sessionId, String command,
                                         BiConsumer<String, Boolean> callback, Consumer<String> feedback) {
        if (command == null || command.trim().isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return submitCommandRequest(sessionId, command, callback, feedback);
    }

    public Future<?> requestAgentStep(String sessionId, String goal, String context,
                                      Consumer<AgentDecision> callback, Consumer<String> feedback) {
        return AI_EXECUTOR.submit(() -> {
            try {
                AIAdapter configured = AIConfigManager.getInstance().getCurrentAIAdapter();
                if (configured == null) throw new IllegalStateException("AI服务未配置");
                var ssh = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getSshService();
                if (ssh == null) throw new IllegalStateException("SSH service is not available");
                AgentRuntime runtime = new AgentRuntime(new AgentToolRegistry(ssh));
                AgentDecision decision = runtime.nextAction(configured, sessionId, goal, context,
                        message -> Platform.runLater(() -> feedback.accept(message)));
                if (!Thread.currentThread().isInterrupted()) Platform.runLater(() -> callback.accept(decision));
            } catch (Exception e) {
                log.error("Agent step failed: {}", e.getMessage(), e);
                if (!Thread.currentThread().isInterrupted())
                    Platform.runLater(() -> feedback.accept("处理AI命令时出错: " + e.getMessage()));
            }
        });
    }

    public Future<?> executeAgentCommand(String sessionId, String command, Consumer<String> callback,
                                         Consumer<String> error) {
        return AI_EXECUTOR.submit(() -> {
            try {
                var ssh = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getSshService();
                if (ssh == null) throw new IllegalStateException("SSH service is not available");
                String output = new SshAgentSessionTool(ssh).executeCommand(sessionId, command);
                if (!Thread.currentThread().isInterrupted()) Platform.runLater(() -> callback.accept(output));
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted())
                    Platform.runLater(() -> error.accept(e.getMessage()));
            }
        });
    }
    
    /**
     * 检查命令是否为cd命令
     */
    private boolean isCdCommand(String command) {
        return command != null && (command.trim().startsWith("cd ") || "cd".equals(command.trim()));
    }
    
    /**
     * 显示错误消息
     */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("AI命令处理错误");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    /**
     * 解释命令的含义
     */
    public String explainCommandSynchronously(String command) {
        if (aiAdapter != null && command != null && !command.isEmpty()) {
            try {
                return aiAdapter.explainCommand(command);
            } catch (Exception e) {
                log.error("解释命令时出错: {}", e.getMessage(), e);
                return "无法解释命令: " + e.getMessage();
            }
        }
        return "AI服务未配置或命令为空";
    }
    
    /**
     * 获取AI帮助
     */
    public String getAIHelpSynchronously(String topic) {
        if (aiAdapter != null && topic != null && !topic.isEmpty()) {
            try {
                return aiAdapter.getHelp(topic);
            } catch (Exception e) {
                log.error("获取AI帮助时出错: {}", e.getMessage(), e);
                return "无法获取帮助: " + e.getMessage();
            }
        }
        return "AI服务未配置或主题为空";
    }
}
