/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.enhancement;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.Optional;

/**
 * AI命令增强器
 * 提供AI驱动的命令处理和增强功能
 */
public class AICommandEnhancer {

    private final AIEnhancementManager aiEnhancementManager;

    public AICommandEnhancer() {
        this.aiEnhancementManager = AIEnhancementManager.getInstance();
    }

    /**
     * 为终端输出区域启用AI增强功能
     */
    public void enableAIEnhancements(InlineCssTextArea outputArea, String tabId) {
        // 添加AI相关的键盘快捷键：addEventFilter 避免被 EnhancedAIModule 立即覆盖（2026-10-23 审计 P1-14）
        outputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            // Ctrl+Shift+A - AI命令建议
            if (event.getCode() == KeyCode.A && event.isControlDown() && event.isShiftDown()) {
                getCommandSuggestion(outputArea);
                event.consume();
            }
            // Ctrl+Shift+E - 解释命令
            else if (event.getCode() == KeyCode.E && event.isControlDown() && event.isShiftDown()) {
                explainCurrentCommand(outputArea);
                event.consume();
            }
            // Ctrl+Shift+N - 自然语言转命令
            else if (event.getCode() == KeyCode.N && event.isControlDown() && event.isShiftDown()) {
                naturalLanguageToCommand(outputArea);
                event.consume();
            }
            // Ctrl+Shift+H - 获取AI帮助
            else if (event.getCode() == KeyCode.H && event.isControlDown() && event.isShiftDown()) {
                getAIHelp(outputArea);
                event.consume();
            }
        });
    }

    /**
     * 获取当前命令的AI建议
     */
    private void getCommandSuggestion(InlineCssTextArea outputArea) {
        String currentLine = getCurrentLine(outputArea);
        if (currentLine != null && !currentLine.trim().isEmpty()) {
            aiEnhancementManager.getSmartCommandSuggestion(outputArea, currentLine.trim());
        } else {
            // 如果当前行为空，提示用户输入
            Platform.runLater(() -> {
                outputArea.appendText("\n请输入部分命令以获取AI建议\n");
            });
        }
    }

    /**
     * 解释当前命令
     */
    private void explainCurrentCommand(InlineCssTextArea outputArea) {
        String currentLine = getCurrentLine(outputArea);
        if (currentLine != null && !currentLine.trim().isEmpty()) {
            aiEnhancementManager.explainCommandDetailed(outputArea, currentLine.trim());
        } else {
            Platform.runLater(() -> {
                outputArea.appendText("\n请输入要解释的命令\n");
            });
        }
    }

    /**
     * 自然语言转命令功能
     */
    private void naturalLanguageToCommand(InlineCssTextArea outputArea) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("自然语言转命令");
        dialog.setHeaderText("输入自然语言描述");
        dialog.setContentText("描述您想执行的操作:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(naturalLanguage -> {
            if (naturalLanguage == null || naturalLanguage.trim().isEmpty()) return;
            // 阻塞 HTTP 必须离开 FX 线程：3 次重试 × 30s 超时会冻结 UI 最长约 90 秒（2026-10-23 审计 P1-9）
            com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().submitIoTask(() -> {
                String command = aiEnhancementManager.naturalLanguageToCommand(naturalLanguage.trim());
                Platform.runLater(() -> {
                    if (command == null || command.isBlank() || command.startsWith("Error")) {
                        outputArea.appendText("\n[AI转换结果] " + (command == null ? "转换失败" : command) + "\n");
                        return;
                    }
                    outputArea.appendText("\n[AI转换结果] " + command + "\n");

                    // 询问用户是否执行命令
                    Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
                    confirmAlert.setTitle("确认执行");
                    confirmAlert.setHeaderText("是否执行以下命令?");
                    confirmAlert.setContentText(command);

                    Optional<ButtonType> confirmResult = confirmAlert.showAndWait();
                    if (confirmResult.isPresent() && confirmResult.get() == ButtonType.OK) {
                        sendCommandToActiveTerminal(command, outputArea);
                    }
                });
                return null;
            });
        });
    }

    /** 把确认后的命令发送到当前活动终端执行（此前确认后只打印一句"已准备好"，是半成品桩）。 */
    private void sendCommandToActiveTerminal(String command, InlineCssTextArea outputArea) {
        Object pane = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("terminalTabPane");
        if (!(pane instanceof javafx.scene.control.TabPane tabPane)) {
            outputArea.appendText("\n没有活动的终端可执行\n");
            return;
        }
        javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) {
            outputArea.appendText("\n没有活动的终端可执行\n");
            return;
        }
        com.runzhi.aishell.service.TerminalCommandService commandService =
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getTerminalCommandService();
        if (commandService == null) {
            outputArea.appendText("\n终端服务不可用\n");
            return;
        }
        commandService.executeShellCommand(selected, command);
        outputArea.appendText("\n已执行: " + command + "\n");
    }

    /**
     * 获取AI帮助
     */
    private void getAIHelp(InlineCssTextArea outputArea) {
        Platform.runLater(() -> {
            javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
            dialog.setTitle("AI帮助");
            dialog.setHeaderText("获取AI帮助");
            dialog.setContentText("输入帮助主题:");

            Optional<String> result = dialog.showAndWait();
            result.ifPresent(topic -> {
                if (topic != null) {
                    aiEnhancementManager.getAdvancedAIHelp(outputArea, topic, "终端使用场景");
                } else {
                    aiEnhancementManager.getAdvancedAIHelp(outputArea, "Linux命令行基础", "终端使用场景");
                }
            });
        });
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
     * 智能执行命令（带AI安全检查）
     */
    public void executeCommandWithAISafety(InlineCssTextArea outputArea, String command, Runnable onExecute) {
        aiEnhancementManager.smartSafetyCheck(outputArea, command, onExecute);
    }

    /**
     * 获取AI驱动的命令补全
     */
    public String getAICommandCompletion(String currentCommand, String workingDirectory, String userInfo) {
        return aiEnhancementManager.getContextualCommandCompletion(currentCommand, workingDirectory, userInfo);
    }

    /**
     * 智能搜索命令历史
     */
    public void searchCommandHistoryIntelligently(InlineCssTextArea outputArea, String searchQuery, java.util.List<String> history) {
        aiEnhancementManager.searchCommandHistoryIntelligently(outputArea, searchQuery, history);
    }

    /**
     * 获取AI增强管理器实例
     */
    public AIEnhancementManager getAIEnhancementManager() {
        return aiEnhancementManager;
    }
}