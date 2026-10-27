/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.enhancement.modules;

import com.runzhi.aishell.ai.enhancement.AICommandEnhancer;
import com.runzhi.aishell.ai.enhancement.AIEnhancementManager;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.InlineCssTextArea;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 增强型AI模块
 * 提供更强大的AI驱动终端增强功能
 */
@Slf4j
public class EnhancedAIModule implements TerminalEnhancementModule {
    
    private final Map<String, Boolean> enabledTabs = new HashMap<>();
    private final AIEnhancementManager aiEnhancementManager;
    private final AICommandEnhancer aiCommandEnhancer;
    private final java.util.Map<InlineCssTextArea, javafx.event.EventHandler<javafx.scene.input.KeyEvent>> keyHandlers =
            new java.util.IdentityHashMap<>();
    
    public EnhancedAIModule() {
        this.aiEnhancementManager = AIEnhancementManager.getInstance();
        this.aiCommandEnhancer = new AICommandEnhancer();
        log.debug("初始化增强型AI模块");
    }
    
    @Override
    public void initialize() {
        // 初始化增强型AI模块
        log.debug("初始化增强型AI模块");
    }

    @Override
    public void enable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.put(tabId, true);
        }
        
        // 使用增强的AI命令处理器
        aiCommandEnhancer.enableAIEnhancements(outputArea, tabId);

        // 添加额外的AI功能键盘事件处理：addEventFilter 避免与其他模块的处理器互相覆盖（2026-10-23 审计 P1-14）
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler =
                event -> handleEnhancedAIKeyPress(event, outputArea, tabId);
        keyHandlers.put(outputArea, handler);
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
    }

    @Override
    public void disable(InlineCssTextArea outputArea, String tabId) {
        if (tabId != null) {
            enabledTabs.remove(tabId);
        }
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> handler = keyHandlers.remove(outputArea);
        if (handler != null) {
            outputArea.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, handler);
        }
    }

    @Override
    public boolean isEnabled(String tabId) {
        return tabId != null && enabledTabs.containsKey(tabId);
    }

    @Override
    public String getModuleName() {
        return "enhanced-ai";
    }

    @Override
    public String getDescription() {
        return "提供增强型AI驱动的终端增强功能";
    }
    
    /**
     * 处理增强型AI相关的键盘按键事件
     */
    private void handleEnhancedAIKeyPress(KeyEvent event, InlineCssTextArea outputArea, String tabId) {
        // 检查是否是增强型AI相关的快捷键
        
        // Ctrl+Alt+S - 获取智能命令建议
        if (event.getCode() == KeyCode.S && event.isControlDown() && event.isAltDown()) {
            getSmartCommandSuggestion(outputArea);
            event.consume();
        } 
        // Ctrl+Alt+E - 获取详细命令解释
        else if (event.getCode() == KeyCode.E && event.isControlDown() && event.isAltDown()) {
            getDetailedCommandExplanation(outputArea);
            event.consume();
        }
        // Ctrl+Alt+F - 智能命令补全
        else if (event.getCode() == KeyCode.F && event.isControlDown() && event.isAltDown()) {
            getSmartCommandCompletion(outputArea, tabId);
            event.consume();
        }
        // Ctrl+Alt+H - 历史命令AI搜索
        else if (event.getCode() == KeyCode.H && event.isControlDown() && event.isAltDown()) {
            searchHistoryWithAI(outputArea);
            event.consume();
        }
    }
    
    /**
     * 获取智能命令建议
     */
    private void getSmartCommandSuggestion(InlineCssTextArea outputArea) {
        String currentLine = getCurrentLine(outputArea);
        if (currentLine != null && !currentLine.trim().isEmpty()) {
            aiEnhancementManager.getSmartCommandSuggestion(outputArea, currentLine.trim());
        }
    }
    
    /**
     * 获取详细命令解释
     */
    private void getDetailedCommandExplanation(InlineCssTextArea outputArea) {
        String currentLine = getCurrentLine(outputArea);
        if (currentLine != null && !currentLine.trim().isEmpty()) {
            aiEnhancementManager.explainCommandDetailed(outputArea, currentLine.trim());
        }
    }
    
    /**
     * 获取智能命令补全
     */
    private void getSmartCommandCompletion(InlineCssTextArea outputArea, String tabId) {
        String currentLine = getCurrentLine(outputArea);
        if (currentLine == null || currentLine.trim().isEmpty()) return;

        // 从当前标签的 SSH 连接解析真实工作目录与用户名，不再使用 "unknown_*" 假上下文（2026-10-23 审计终端-9）
        String workingDirectory = resolveWorkingDirectory(tabId);
        String userInfo = System.getProperty("user.name", "");

        // AI 补全是阻塞 HTTP：在 FX 线程执行会冻结 UI 最长约 90 秒（2026-10-23 审计 P1-9）
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().submitIoTask(() -> {
            String completedCommand = aiEnhancementManager.getContextualCommandCompletion(
                    currentLine.trim(), workingDirectory, userInfo);
            if (completedCommand == null || completedCommand.isBlank()
                    || completedCommand.equals(currentLine.trim()) || completedCommand.startsWith("Error")) {
                return null;
            }
            String replacement = completedCommand;
            Platform.runLater(() -> {
                String fullText = outputArea.getText();
                if (fullText != null) {
                    int caretPos = outputArea.getCaretPosition();
                    int lineStart = findLineStart(fullText, caretPos);
                    int lineEnd = findLineEnd(fullText, caretPos);

                    String beforeLine = fullText.substring(0, lineStart);
                    String afterLine = fullText.substring(lineEnd);

                    outputArea.replaceText(beforeLine + replacement + afterLine);
                    outputArea.moveTo(lineStart + replacement.length());
                }
            });
            return null;
        });
    }

    /** 当前标签远程工作目录（无连接时回退本地目录）。 */
    private String resolveWorkingDirectory(String tabId) {
        try {
            com.runzhi.aishell.ssh.SSHService sshService =
                    com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getSshService();
            if (sshService != null && tabId != null) {
                String remote = sshService.getCurrentWorkingDirectoryForTab(tabId);
                if (remote != null && !remote.isBlank()) return remote;
            }
        } catch (Exception ignored) { }
        return System.getProperty("user.dir", "");
    }
    
    /**
     * AI驱动的历史命令搜索
     */
    private void searchHistoryWithAI(InlineCssTextArea outputArea) {
        // 打开真实的命令历史对话框（含搜索与执行），替代此前只打日志的演示实现（2026-10-23 审计终端）
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getNavigationManager()
                    .openInNewWindow("command-history-dialog.fxml", "命令历史");
        } catch (Exception e) {
            log.warn("无法打开命令历史对话框: {}", e.getMessage());
        }
    }
    
    /**
     * 获取当前行的文本
     */
    private String getCurrentLine(InlineCssTextArea outputArea) {
        String fullText = outputArea.getText();
        if (fullText == null || fullText.isEmpty()) {
            return "";
        }
        
        int caretPos = outputArea.getCaretPosition();
        String[] lines = fullText.split("\n", -1);
        int currentLineIndex = 0;
        int currentPos = 0;

        for (String line : lines) {
            if (line != null && caretPos <= currentPos + line.length()) {
                return line;
            }
            currentPos += line.length() + 1; // +1 for newline character
            currentLineIndex++;
        }
        return "";
    }
    
    /**
     * 查找行的开始位置
     */
    private int findLineStart(String text, int caretPos) {
        if (text == null) return 0;
        int lineStart = text.lastIndexOf('\n', caretPos - 1);
        if (lineStart == -1) lineStart = 0;
        else lineStart++; // 如果没有找到换行符，从文本开头开始
        return lineStart;
    }

    /**
     * 查找行的结束位置
     */
    private int findLineEnd(String text, int caretPos) {
        if (text == null) return 0;
        int lineEnd = text.indexOf('\n', caretPos);
        if (lineEnd == -1) lineEnd = text.length(); // 如果没有找到换行符，到文本结尾结束
        return lineEnd;
    }
    
    /**
     * 智能执行命令（带AI安全检查）
     */
    public void executeCommandWithAISafety(InlineCssTextArea outputArea, String command, Runnable onExecute) {
        aiEnhancementManager.smartSafetyCheck(outputArea, command, onExecute);
    }
    
    /**
     * 获取AI命令补全
     */
    public String getAICommandCompletion(String partialCommand, String workingDirectory, String userInfo) {
        return aiEnhancementManager.getContextualCommandCompletion(partialCommand, workingDirectory, userInfo);
    }
    
    /**
     * 搜索命令历史（AI驱动）
     */
    public void searchCommandHistoryAI(InlineCssTextArea outputArea, String searchQuery, java.util.List<String> history) {
        aiEnhancementManager.searchCommandHistoryIntelligently(outputArea, searchQuery, history);
    }
    
    /**
     * 获取AI帮助
     */
    public void getAIHelp(InlineCssTextArea outputArea, String topic) {
        aiEnhancementManager.getAdvancedAIHelp(outputArea, topic, "终端使用场景");
    }
    
    /**
     * 获取AI增强管理器
     */
    public AIEnhancementManager getAIEnhancementManager() {
        return aiEnhancementManager;
    }
    
    /**
     * 获取AI命令增强器
     */
    public AICommandEnhancer getAICommandEnhancer() {
        return aiCommandEnhancer;
    }
}