/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.ai;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.ai.AIService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI增强功能工具类
 * 提供AI驱动的终端增强功能
 */
@Slf4j
public class AIEnhancementUtil {

    private static volatile AIEnhancementUtil instance;
    private AIAdapter aiAdapter;
    private AIService aiService; // 懒加载，初始为 null
    
    // 添加缓存机制以提高性能
    private final ConcurrentHashMap<String, String> commandCache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    
    // 添加使用统计
    private final AtomicInteger aiRequests = new AtomicInteger(0);
    private final AtomicInteger aiFailures = new AtomicInteger(0);

    public AIEnhancementUtil() {
        // 不在构造函数中获取 AIService，避免循环依赖
        // aiService 将在首次使用时懒加载获取
        this.aiService = null;
        this.aiAdapter = null;
    }

    public static synchronized AIEnhancementUtil getInstance() {
        if (instance == null) {
            instance = new AIEnhancementUtil();
        }
        return instance;
    }

    /**
     * 懒加载获取 AIService
     */
    private AIService getAIServiceLazy() {
        if (aiService == null) {
            try {
                return AIShellModuleManager.getInstance().getAiService();
            } catch (Exception e) {
                log.warn("Failed to get AIService, will use null", e);
                return null;
            }
        }
        return aiService;
    }
        
    /**
     * 懒加载初始化 AI 适配器
     */
    private void lazyInitializeAIAdapter() {
        if (aiAdapter == null) {
            AIService service = getAIServiceLazy();
            if (service != null) {
                aiAdapter = service.isConfigured() ?
                        AIConfigManager.getInstance().getCurrentAIAdapter() : null;
    
                if (aiAdapter == null) {
                    // 如果没有配置，尝试使用默认配置
                    AIConfigManager.getInstance().setCurrentConfig("默认OpenAI");
                    aiAdapter = AIConfigManager.getInstance().getCurrentAIAdapter();
                }
            }
        }
    }
    
    /**
     * 初始化 AI 适配器
     */
    private void initializeAIAdapter() {
        // 改为懒加载方式
        lazyInitializeAIAdapter();
    }
    
    /**
     * 重新初始化 AI 适配器
     */
    public void reinitializeAIAdapter() {
        // 重置 aiAdapter 以便重新获取
        this.aiAdapter = null;
        lazyInitializeAIAdapter();
    }
    
    /**
     * 获取AI请求统计信息
     */
    public String getStats() {
        return String.format(
            "AI Enhancement Util Stats:\n" +
            "- Total Requests: %d\n" +
            "- Failures: %d\n" +
            "- Cache Hits: %d\n" +
            "- Cache Misses: %d\n" +
            "- Success Rate: %.2f%%",
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

    /**
     * 获取 AI 命令建议
     */
    public void getSuggestionForCommand(InlineCssTextArea outputArea, String partialCommand) {
        // 确保 AI 适配器已初始化
        lazyInitializeAIAdapter();
            
        if (aiAdapter == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n错误: AI服务未配置，请先配置AI服务\n");
            });
            aiFailures.incrementAndGet();
            return;
        }

        // 检查缓存
        String cachedSuggestion = commandCache.get("suggestion:" + partialCommand);
        if (cachedSuggestion != null) {
            cacheHits.incrementAndGet();
            Platform.runLater(() -> {
                outputArea.appendText("\nAI建议 (缓存): " + cachedSuggestion + "\n");
            });
            return;
        }

        // 异步获取AI建议
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = "基于用户输入的命令片段 '" + partialCommand + "', 提供完整的命令建议。" +
                               "只返回建议的命令，不要添加任何解释。";

                aiRequests.incrementAndGet();
                String result = aiAdapter.sendQuery(prompt);
                
                // 缓存结果
                if (result != null && !result.isEmpty()) {
                    commandCache.put("suggestion:" + partialCommand, result);
                    cacheMisses.incrementAndGet();
                }
                
                return result;
            } catch (Exception e) {
                aiFailures.incrementAndGet();
                return "AI建议获取失败: " + e.getMessage();
            }
        }).thenAcceptAsync(suggestion -> {
            if (suggestion != null && !suggestion.isEmpty()) {
                Platform.runLater(() -> {
                    // 显示建议的提示
                    Tooltip tooltip = new Tooltip(suggestion);
                    tooltip.show(outputArea,
                                outputArea.localToScreen(outputArea.getBoundsInLocal()).getMinX() + 10,
                                outputArea.localToScreen(outputArea.getBoundsInLocal()).getMinY() + 10);

                    // 可以选择将建议插入到命令行
                    outputArea.appendText("\nAI建议: " + suggestion + "\n");
                });
            }
        }, Platform::runLater);
    }
    
    /**
     * 获取 AI 命令建议（同步版本）
     */
    public String getSuggestionForCommandSync(String partialCommand) {
        // 确保 AI 适配器已初始化
        lazyInitializeAIAdapter();
            
        if (aiAdapter == null) {
            return "错误: AI服务未配置，请先配置AI服务";
        }

        // 检查缓存
        String cachedSuggestion = commandCache.get("suggestion:" + partialCommand);
        if (cachedSuggestion != null) {
            cacheHits.incrementAndGet();
            return cachedSuggestion;
        }
        
        cacheMisses.incrementAndGet();
        
        try {
            String prompt = "基于用户输入的命令片段 '" + partialCommand + "', 提供完整的命令建议。" +
                           "只返回建议的命令，不要添加任何解释。";
            
            aiRequests.incrementAndGet();
            String result = aiAdapter.sendQuery(prompt);
            
            // 缓存结果
            if (result != null && !result.isEmpty()) {
                commandCache.put("suggestion:" + partialCommand, result);
            }
            
            return result != null ? result : "未能获取AI建议";
        } catch (Exception e) {
            aiFailures.incrementAndGet();
            return "AI建议获取失败: " + e.getMessage();
        }
    }

    /**
     * 获取命令解释
     */
    public void explainCommand(InlineCssTextArea outputArea, String command) {
        // 确保 AI 适配器已初始化
        lazyInitializeAIAdapter();
        
        if (aiAdapter == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n错误: AI服务未配置，请先配置AI服务\n");
            });
            return;
        }

        // 异步获取命令解释
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = "请解释Linux命令 '" + command + "' 的作用和用法。简洁明了地说明其功能和常见选项。";

                return aiAdapter.sendQuery(prompt);
            } catch (Exception e) {
                return "命令解释获取失败: " + e.getMessage();
            }
        }).thenAcceptAsync(explanation -> {
            if (explanation != null && !explanation.isEmpty()) {
                Platform.runLater(() -> {
                    outputArea.appendText("\n命令解释: " + explanation + "\n");
                });
            }
        }, Platform::runLater);
    }

    /**
     * 获取安全的命令确认
     */
    public void confirmPotentiallyDangerousCommand(InlineCssTextArea outputArea, String command, Runnable onConfirmed) {
        // 检测潜在危险命令
        if (isPotentiallyDangerousCommand(command)) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("危险命令确认");
                alert.setHeaderText("检测到潜在危险命令");
                alert.setContentText("您即将执行命令: " + command + "\n\n此命令可能对系统造成影响，确定要继续执行吗？");

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    onConfirmed.run();
                } else {
                    outputArea.appendText("命令执行已取消\n");
                }
            });
        } else {
            onConfirmed.run();
        }
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
            "umount\\s+/"     // 卸载根目录
        };

        for (String pattern : dangerousPatterns) {
            if (lowerCmd.matches(".*" + pattern + ".*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 智能命令补全
     */
    public String getAICommandCompletion(String partialCommand) {
        // 确保 AI 适配器已初始化
        lazyInitializeAIAdapter();
        
        if (aiAdapter == null) {
            return partialCommand; // 返回原命令
        }

        try {
            String prompt = "基于命令片段 '" + partialCommand + "' 提供最可能的完整命令。" +
                           "如果片段对应多个可能的命令，请返回最常用的那个。只返回命令本身，不要解释。";

            String response = aiAdapter.sendQuery(prompt);
            if (response != null && !response.trim().isEmpty()) {
                return response.trim();
            }
        } catch (Exception e) {
            // 如果AI请求失败，返回原命令
        }

        return partialCommand;
    }

    /**
     * 获取 AI 驱动的命令历史搜索
     */
    public void searchCommandHistoryAI(InlineCssTextArea outputArea, String searchQuery, List<String> history) {
        // 确保 AI 适配器已初始化
        lazyInitializeAIAdapter();
            
        if (aiAdapter == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n错误: AI服务未配置，请先配置AI服务\n");
            });
            return;
        }

        // 异步执行AI搜索
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = "从以下命令历史中找出与 '" + searchQuery + "' 相关的命令：" +
                               String.join("; ", history) +
                               "。返回最相关的命令，如果有多个相关命令，按相关性排序返回。";

                return aiAdapter.sendQuery(prompt);
            } catch (Exception e) {
                return "AI搜索失败: " + e.getMessage();
            }
        }).thenAcceptAsync(result -> {
            if (result != null && !result.isEmpty()) {
                Platform.runLater(() -> {
                    outputArea.appendText("\nAI搜索结果: " + result + "\n");
                });
            }
        }, Platform::runLater);
    }

    /**
     * 获取 AI 帮助
     */
    public void getAIHelp(InlineCssTextArea outputArea, String topic) {
        // 确保 AI 适配器已初始化
        lazyInitializeAIAdapter();
            
        if (aiAdapter == null) {
            Platform.runLater(() -> {
                outputArea.appendText("\n错误: AI服务未配置，请先配置AI服务\n");
            });
            return;
        }

        String finalTopic = topic != null && !topic.trim().isEmpty() ? topic : "Linux命令行使用技巧";

        // 异步获取帮助
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = "提供关于 '" + finalTopic + "' 的详细帮助信息，特别是针对Linux命令行用户的实用建议。";

                return aiAdapter.sendQuery(prompt);
            } catch (Exception e) {
                return "AI帮助获取失败: " + e.getMessage();
            }
        }).thenAcceptAsync(helpContent -> {
            if (helpContent != null && !helpContent.isEmpty()) {
                Platform.runLater(() -> {
                    outputArea.appendText("\n=== AI帮助: " + finalTopic + " ===\n");
                    outputArea.appendText(helpContent + "\n");
                    outputArea.appendText("=========================\n");
                });
            }
        }, Platform::runLater);
    }

    public AIAdapter getAiAdapter() {
        return aiAdapter;
    }

    public AIService getAiService() {
        return aiService;
    }
}