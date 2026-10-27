/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.enhancement;

import com.runzhi.aishell.common.ai.AIEnhancementUtil;
import com.runzhi.aishell.common.ai.AIHelperService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI增强管理器
 * 提供高级AI增强功能，包括智能命令建议、自然语言处理、安全检查等
 */
public class AIEnhancementManager {

    private static volatile AIEnhancementManager instance;
    private final AIEnhancementUtil aiEnhancementUtil;
    private final AIHelperService aiHelperService;
    private final ConcurrentHashMap<String, String> commandCache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    private final AtomicInteger aiRequests = new AtomicInteger(0);
    private final AtomicInteger aiFailures = new AtomicInteger(0);

    private AIEnhancementManager() {
        this.aiEnhancementUtil = AIEnhancementUtil.getInstance();
        this.aiHelperService = new AIHelperService();
    }

    public static synchronized AIEnhancementManager getInstance() {
        if (instance == null) {
            instance = new AIEnhancementManager();
        }
        return instance;
    }

    /**
     * 获取AI驱动的智能命令建议
     */
    public void getSmartCommandSuggestion(InlineCssTextArea outputArea, String userInput) {
        if (aiEnhancementUtil.getAiAdapter() == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n[AI错误] AI服务未配置，请先配置AI服务\n");
            });
            aiFailures.incrementAndGet();
            return;
        }

        // 检查缓存
        String cacheKey = "smart_suggestion:" + userInput;
        String cachedSuggestion = commandCache.get(cacheKey);
        if (cachedSuggestion != null) {
            cacheHits.incrementAndGet();
            Platform.runLater(() -> {
                outputArea.appendText("\n[AI建议] " + cachedSuggestion + "\n");
            });
            return;
        }

        // 异步获取智能建议
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = String.format(
                    "基于用户输入 '%s'，请提供最适合的Linux命令建议。" +
                    "如果你认为用户想要执行某种操作但没有明确说明，请推测其意图并给出相应的命令。" +
                    "只返回建议的命令，不要添加任何解释。" +
                    "如果不确定用户意图，请返回'无法确定您的意图，请提供更多细节'。", 
                    userInput
                );

                aiRequests.incrementAndGet();
                String result = aiEnhancementUtil.getAiAdapter().sendQuery(prompt);
                
                // 仅缓存有效结果：失败响应（"Error:"、"AI建议获取失败"）一旦入缓存，
                // 相同输入就永远命中缓存的错误文本（2026-10-23 审计 P1-8）
                if (result != null && !result.isEmpty()
                        && !result.contains("无法确定")
                        && !result.startsWith("Error:")
                        && !result.contains("AI建议获取失败")) {
                    commandCache.put(cacheKey, result);
                }
                
                return result;
            } catch (Exception e) {
                aiFailures.incrementAndGet();
                return "AI建议获取失败: " + e.getMessage();
            }
        }).thenAcceptAsync(suggestion -> {
            if (suggestion != null && !suggestion.isEmpty()) {
                Platform.runLater(() -> {
                    // 显示建议
                    outputArea.appendText("\n[AI建议] " + suggestion + "\n");
                    
                    // 显示提示框（如果建议是有效命令）
                    if (!suggestion.contains("无法确定") && !suggestion.contains("AI建议获取失败")) {
                        Tooltip tooltip = new Tooltip("AI建议: " + suggestion);
                        tooltip.show(outputArea,
                                outputArea.localToScreen(outputArea.getBoundsInLocal()).getMinX() + 10,
                                outputArea.localToScreen(outputArea.getBoundsInLocal()).getMinY() + 10);
                    }
                });
            }
        }, Platform::runLater);
    }

    /**
     * 自然语言转命令功能
     */
    public String naturalLanguageToCommand(String naturalLanguage) {
        if (aiEnhancementUtil.getAiAdapter() == null) {
            return "AI服务未配置，请先配置AI服务";
        }

        try {
            String prompt = String.format(
                "将以下自然语言转换为对应的Linux命令: '%s'" +
                "只返回具体的命令，不要添加任何解释或说明。", 
                naturalLanguage
            );

            aiRequests.incrementAndGet();
            String result = aiEnhancementUtil.getAiAdapter().sendQuery(prompt);
            
            // 缓存结果
            if (result != null && !result.isEmpty()) {
                commandCache.put("nl_to_cmd:" + naturalLanguage, result);
            }
            
            return result != null ? result : "无法生成命令";
        } catch (Exception e) {
            aiFailures.incrementAndGet();
            return "命令转换失败: " + e.getMessage();
        }
    }

    /**
     * 获取命令解释
     */
    public void explainCommandDetailed(InlineCssTextArea outputArea, String command) {
        if (aiEnhancementUtil.getAiAdapter() == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n[AI错误] AI服务未配置，请先配置AI服务\n");
            });
            return;
        }

        // 异步获取详细命令解释
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = String.format(
                    "详细解释Linux命令 '%s' 的功能、常用选项和使用示例。" +
                    "请按照以下格式组织回复：" +
                    "1. 命令功能概述\n" +
                    "2. 主要选项说明\n" +
                    "3. 使用示例（至少2个）\n" +
                    "4. 注意事项", 
                    command
                );

                return aiEnhancementUtil.getAiAdapter().sendQuery(prompt);
            } catch (Exception e) {
                return "命令解释获取失败: " + e.getMessage();
            }
        }).thenAcceptAsync(explanation -> {
            if (explanation != null && !explanation.isEmpty()) {
                Platform.runLater(() -> {
                    outputArea.appendText("\n=== 命令详解: " + command + " ===\n");
                    outputArea.appendText(explanation + "\n");
                    outputArea.appendText("================================\n");
                });
            }
        }, Platform::runLater);
    }

    /**
     * 智能安全检查
     */
    public void smartSafetyCheck(InlineCssTextArea outputArea, String command, Runnable onConfirmed) {
        // 首先检查是否为危险命令
        boolean isDangerous = isPotentiallyDangerousCommand(command);
        boolean isComplex = isComplexCommand(command);
        
        if (isDangerous || isComplex) {
            Platform.runLater(() -> {
                Alert alert = new Alert(isDangerous ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION);
                alert.setTitle(isDangerous ? "危险命令确认" : "复杂命令提醒");
                alert.setHeaderText(isDangerous ? "检测到潜在危险命令" : "检测到复杂命令");
                
                String warningMsg = isDangerous ? 
                    "您即将执行一个可能对系统造成重大影响的命令: " + command + "\n\n确定要继续执行吗？" :
                    "您即将执行一个较为复杂的命令: " + command + "\n\n请确认是否正确输入。";
                
                alert.setContentText(warningMsg);

                ButtonType helpButtonType = null; // Initialize outside the if block
                if (isComplex) {
                    alert.getButtonTypes().clear();
                    helpButtonType = new ButtonType("帮助");
                    alert.getButtonTypes().addAll(ButtonType.YES, ButtonType.NO, helpButtonType);
                } else {
                    // For dangerous commands, only YES/NO buttons
                    alert.getButtonTypes().clear();
                    alert.getButtonTypes().addAll(ButtonType.YES, ButtonType.NO);
                }

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent()) {
                    if (result.get() == ButtonType.YES || result.get() == ButtonType.OK) {
                        onConfirmed.run();
                    } else if (helpButtonType != null && result.get() == helpButtonType) {
                        // 如果用户点击了帮助，提供命令解释
                        explainCommandDetailed(outputArea, command);
                        alert.close();
                    } else {
                        outputArea.appendText("命令执行已取消\n");
                    }
                }
            });
        } else {
            onConfirmed.run();
        }
    }

    /**
     * 检测复杂命令
     */
    private boolean isComplexCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        // 检测管道、重定向、通配符等复杂操作
        String lowerCmd = command.toLowerCase().trim();
        String[] complexityIndicators = {
            "\\|",      // 管道
            "&&",       // 逻辑与
            "\\|\\|",   // 逻辑或
            ";",        // 命令分隔符
            "\\*",      // 通配符
            "\\$\\(",   // 命令替换
            "`[^`]+`",  // 反引号命令替换
            ">",        // 重定向
            ">>",       // 追加重定向
            "<"         // 输入重定向
        };

        for (String indicator : complexityIndicators) {
            if (lowerCmd.matches(".*" + indicator + ".*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测潜在危险命令
     */
    private boolean isPotentiallyDangerousCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String lowerCmd = command.toLowerCase().trim();

        // 常见的危险命令模式
        String[] dangerousPatterns = {
            "rm\\s+-rf",      // 强制删除
            "rm\\s+/",        // 删除根目录
            "mv\\s+.*\\s+/",  // 移动到根目录
            "dd\\s+if=",      // 磁盘操作
            "mkfs\\s+",       // 格式化磁盘
            "fdisk\\s+",      // 分区操作
            ">\\s*/etc/",     // 重定向到系统配置文件
            ">/dev/sd",       // 重定向到设备文件
            "chmod\\s+777\\s+", // 设置过度权限
            "chmod\\s+-R\\s+777", // 递归设置过度权限
            "chown\\s+-R\\s+.*\\s+/home/", // 递归更改home目录所有权
            "mount\\s+-o\\s+remount", // 重新挂载
            "umount\\s+/",    // 卸载根目录
            "rm.*\\s+/.*",    // 删除任意路径下的文件
            "cat\\s+.*\\s+>/etc/", // 写入系统配置文件
            "echo.*>/etc/",   // 写入系统配置文件
            "printf.*>/etc/"  // 写入系统配置文件
        };

        for (String pattern : dangerousPatterns) {
            if (lowerCmd.matches(".*" + pattern + ".*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取AI驱动的上下文感知命令补全
     */
    public String getContextualCommandCompletion(String currentCommand, String workingDirectory, String userInfo) {
        if (aiEnhancementUtil.getAiAdapter() == null) {
            return currentCommand; // 返回原命令
        }

        try {
            String context = "当前工作目录: " + (workingDirectory != null ? workingDirectory : "未知") + 
                           ", 当前用户: " + (userInfo != null ? userInfo : "未知");
            
            String prompt = String.format(
                "基于当前环境('%s')和命令片段('%s')，提供最可能的完整命令。" +
                "考虑常见的使用场景和上下文，返回最合适的命令。" +
                "只返回命令本身，不要解释。", 
                context, currentCommand
            );

            String response = aiEnhancementUtil.getAiAdapter().sendQuery(prompt);
            if (response != null && !response.trim().isEmpty()) {
                return response.trim();
            }
        } catch (Exception e) {
            // 如果AI请求失败，返回原命令
        }

        return currentCommand;
    }

    /**
     * 获取AI驱动的历史命令智能搜索
     */
    public void searchCommandHistoryIntelligently(InlineCssTextArea outputArea, String searchQuery, List<String> history) {
        if (aiEnhancementUtil.getAiAdapter() == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n[AI错误] AI服务未配置，请先配置AI服务\n");
            });
            return;
        }

        // 异步执行智能搜索
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = String.format(
                    "从以下命令历史中找出与 '%s' 语义最相关的命令：" +
                    "%s" +
                    "请按相关性排序返回最匹配的命令，并简要说明为什么这些命令相关。", 
                    searchQuery, String.join("; ", history)
                );

                return aiEnhancementUtil.getAiAdapter().sendQuery(prompt);
            } catch (Exception e) {
                return "AI搜索失败: " + e.getMessage();
            }
        }).thenAcceptAsync(result -> {
            if (result != null && !result.isEmpty()) {
                Platform.runLater(() -> {
                    outputArea.appendText("\n[AI智能搜索结果]\n");
                    outputArea.appendText(result + "\n");
                });
            }
        }, Platform::runLater);
    }

    /**
     * 获取高级AI帮助
     */
    public void getAdvancedAIHelp(InlineCssTextArea outputArea, String topic, String context) {
        if (aiEnhancementUtil.getAiAdapter() == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n[AI错误] AI服务未配置，请先配置AI服务\n");
            });
            return;
        }

        String finalTopic = topic != null && !topic.trim().isEmpty() ? topic : "Linux命令行使用技巧";
        String finalContext = context != null ? context : "一般使用场景";

        // 异步获取高级帮助
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = String.format(
                    "提供关于 '%s' 的详细帮助信息，特别考虑上下文 '%s'。" +
                    "请包括：1. 基本概念 2. 实际应用场景 3. 最佳实践 4. 常见陷阱和解决方案", 
                    finalTopic, finalContext
                );

                return aiEnhancementUtil.getAiAdapter().sendQuery(prompt);
            } catch (Exception e) {
                return "AI帮助获取失败: " + e.getMessage();
            }
        }).thenAcceptAsync(helpContent -> {
            if (helpContent != null && !helpContent.isEmpty()) {
                Platform.runLater(() -> {
                    outputArea.appendText("\n=== 高级AI帮助: " + finalTopic + " ===\n");
                    outputArea.appendText(helpContent + "\n");
                    outputArea.appendText("=====================================\n");
                });
            }
        }, Platform::runLater);
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format(
            "AI增强管理器统计:\n" +
            "- AI请求总数: %d\n" +
            "- 请求失败数: %d\n" +
            "- 缓存命中数: %d\n" +
            "- 缓存未命中数: %d\n" +
            "- 整体成功率: %.2f%%",
            aiRequests.get(),
            aiFailures.get(),
            cacheHits.get(),
            cacheMisses.get(),
            aiRequests.get() > 0 ? ((double)(aiRequests.get() - aiFailures.get()) / aiRequests.get()) * 100 : 0
        );
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        aiRequests.set(0);
        aiFailures.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        commandCache.clear();
    }

    public AIEnhancementUtil getAiEnhancementUtil() {
        return aiEnhancementUtil;
    }

    public AIHelperService getAiHelperService() {
        return aiHelperService;
    }
}