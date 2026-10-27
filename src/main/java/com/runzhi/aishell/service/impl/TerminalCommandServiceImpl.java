/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.service.impl;

import com.runzhi.aishell.ai.processor.AICommandProcessor;
import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.agent.AgentDecision;
import com.runzhi.aishell.agent.AgentSandboxPolicy;
import com.runzhi.aishell.agent.CommandRisk;
import com.runzhi.aishell.agent.CommandRiskAssessor;
import com.runzhi.aishell.agent.ReadOnlyCommandWhitelist;
import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.terminal.ShellCommandSimulator;
import com.runzhi.aishell.service.TerminalCommandService;
import com.runzhi.aishell.service.TerminalConnectionService;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.terminal.enhancement.TerminalEnhancementManager;
import com.runzhi.aishell.terminal.handler.history.CommandHistoryHandler;
import javafx.application.Platform;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.Parent;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.InlineCssTextArea;


import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 终端命令服务实现
 * 负责命令的执行、处理、历史管理等
 */
@Slf4j
public class TerminalCommandServiceImpl implements TerminalCommandService {
    

    private final CommandHistoryHandler commandHistoryHandler = new CommandHistoryHandler();
    private AICommandProcessor aiCommandProcessor;
    private ShellCommandSimulator shellCommandSimulator;

    // Tab 命令历史映射
    private final Map<Tab, List<String>> tabCommandHistory = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Tab, Integer> tabCurrentHistoryIndex = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 为每个 Tab 维护独立的当前工作目录
    private final Map<Tab, String> tabCurrentWorkingDirectories = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Tab, Future<?>> activeAIRequests = new ConcurrentHashMap<>();
    private final Map<Tab, AgentTaskState> activeAgentTasks = new ConcurrentHashMap<>();
    private final Map<String, String> agentConversationContexts = new ConcurrentHashMap<>();
    private static final int MAX_AGENT_CONTEXT = 256 * 1024;

    private static final class AgentTaskState {
        final String goal;
        String context = "";
        int commands;
        volatile boolean cancelled;
        AgentTaskState(String goal) { this.goal = goal; }
    }

    // Deprecated - 此方法已废弃，SSH 服务将从模块管理器懒加载获取
    @SuppressWarnings("deprecated")
    public void setDependencies(SSHService sshService) {
        log.debug("setDependencies deprecated - SSH service will be lazily obtained from module manager");
    }
    
    /**
     * 获取 SSH 服务 - 从模块管理器懒加载获取
     */
    private SSHService getSSHService() {
        try {
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                return moduleManager.getSshService();
            }
        } catch (Exception e) {
            log.error("Failed to get SSH service from module manager: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 获取或创建 AICommandProcessor（懒加载模式）
     */
    private AICommandProcessor getAICommandProcessor() {
        if (this.aiCommandProcessor == null) {
            synchronized (this) {
                if (this.aiCommandProcessor == null) {
                    this.aiCommandProcessor = new AICommandProcessor();
                    log.info("AICommandProcessor lazily initialized");
                }
            }
        }
        return this.aiCommandProcessor;
    }

    @Override
    public void executeCurrentCommandInTab(Tab tab, TextField input, ChoiceBox<String> modeComboBox) {
        if (tab == null) {
            log.warn("标签页为空，无法执行命令");
            return;
        }

        String command;
        if (input != null) {
            command = input.getText();
            log.debug("获取到命令：{}", command);
        } else {
            log.warn("命令输入框为空，无法执行命令");
            return;
        }

        if (command != null && !command.trim().isEmpty()) {
            log.debug("执行命令：{}", command);

            // 检查是否为 AI 命令（以 ai:开头）
            if (command.toLowerCase().startsWith("ai:")) {
                String aiCommand = command.substring(3).trim();
                executeAICommandDirectly(tab, aiCommand);
            } else {
                String mode = modeComboBox != null ? modeComboBox.getValue() : "普通模式";
                log.debug("当前模式：{}", mode);

                if ("AI 模式".equals(mode) || "AI模式".equals(mode)) {
                    processAITerminalCommand(tab, command);
                } else {
                    commandHistoryHandler.addCommandToHistory(tab, command);
                    executeCommandInTab(tab, command);
                }
            }

            input.clear();
            input.requestFocus();
            input.positionCaret(0);
        } else {
            log.debug("命令为空，不执行任何操作");
        }
    }

    @Override
    public void executeCommandInTabFromOutput(Tab tab, String command) {
        if (tab == null || command == null || command.trim().isEmpty()) return;
        commandHistoryHandler.addCommandToHistory(tab, command);
        executeCommandInTab(tab, command);
    }

    @Override
    public void initializeTabCommandHistory(Tab tab) {
        commandHistoryHandler.initializeTabCommandHistory(tab);
    }

    @Override
    public void executeAICommandDirectly(Tab tab, String command) {
        if (tab == null || command == null || command.trim().isEmpty()) return;
        processAICommand(tab, command, true);
    }

    @Override
    public void processAITerminalCommand(Tab tab, String command) {
        if (tab == null || command == null || command.trim().isEmpty()) return;
        processAICommand(tab, command, false);
    }

    @Override
    public void cancelAIRequest(Tab tab) {
        if (tab == null) return;
        Future<?> request = activeAIRequests.remove(tab);
        if (request != null && !request.isDone()) request.cancel(true);
        AgentTaskState task = activeAgentTasks.remove(tab);
        if (task != null) task.cancelled = true;
        publish(tab, AgentActivityBus.Kind.CANCELLED, "用户已停止当前 Agent 请求");
        writeAIFeedback(tab, "[AI] 已停止当前请求");
    }

    private void processAICommand(Tab tab, String command, boolean isDirectCommand) {
        if (tab == null || command == null || command.trim().isEmpty()) return;

        writeAIFeedback(tab, "[AI] 请求：" + command + "\n[AI] 正在生成命令...");
        publish(tab, AgentActivityBus.Kind.USER, command);
        publish(tab, AgentActivityBus.Kind.STATUS, "正在分析目标并生成安全命令");
        Future<?> previous = activeAIRequests.remove(tab);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
            writeAIFeedback(tab, "[AI] 已取消上一个请求");
            publish(tab, AgentActivityBus.Kind.CANCELLED, "新请求已替换上一个请求");
        }
        
        AgentTaskState oldTask = activeAgentTasks.remove(tab);
        if (oldTask != null) oldTask.cancelled = true;
        AgentTaskState task = new AgentTaskState(command.trim());
        String previousContext = agentConversationContexts.get(agentConversationKey(tab));
        task.context = (previousContext == null ? "" : previousContext)
                + "\n\nUSER_FOLLOW_UP:\n" + command.trim();
        activeAgentTasks.put(tab, task);
        requestNextAgentStep(tab, task);
    }

    private void requestNextAgentStep(Tab tab, AgentTaskState task) {
        if (!isCurrentTask(tab, task)) return;
        publish(tab, AgentActivityBus.Kind.STATUS,
                task.commands == 0 ? "正在规划任务" : "正在结合第 " + task.commands + " 步结果规划下一步");
        Future<?> request = getAICommandProcessor().requestAgentStep(requireTabId(tab), task.goal, task.context,
                decision -> {
                    activeAIRequests.remove(tab);
                    if (!isCurrentTask(tab, task)) return;
                    if (decision.type() == AgentDecision.Type.COMPLETE) {
                        finishAgentTask(tab, task, AgentActivityBus.Kind.SUCCESS, decision.content());
                    } else if (decision.type() == AgentDecision.Type.ASK) {
                        task.context = decision.context();
                        askAgentQuestion(tab, task, decision.content(), decision.options());
                    } else {
                        task.context = decision.context();
                        handleAICommandConfirmation(tab, task, decision.content());
                    }
                }, feedback -> publishAgentFeedback(tab, feedback));
        activeAIRequests.put(tab, request);
    }

    private boolean isCurrentTask(Tab tab, AgentTaskState task) {
        return task != null && !task.cancelled && activeAgentTasks.get(tab) == task;
    }

    private String requireTabId(Tab tab) {
        String tabId = tab == null ? null : tab.getId();
        if (tabId == null || tabId.isBlank()) {
            throw new IllegalStateException("当前终端尚未绑定 SSH 会话");
        }
        return tabId;
    }

    private void handleAICommandConfirmation(Tab tab, String command, boolean isCdCommand) {
        AgentTaskState task = activeAgentTasks.get(tab);
        if (task == null) return;
        handleAICommandConfirmation(tab, task, command);
    }

    private void handleAICommandConfirmation(Tab tab, AgentTaskState task, String command) {
        AgentSandboxPolicy.Verdict sandbox = AgentSandboxPolicy.validate(command);
        if (!sandbox.allowed()) {
            publish(tab, AgentActivityBus.Kind.TOOL, "沙箱拒绝命令：" + sandbox.reason() + "\n" + command);
            appendExecutionContext(task, command, false, "sandbox rejected: " + sandbox.reason());
            requestNextAgentStep(tab, task);
            return;
        }
        CommandRisk risk = CommandRiskAssessor.assess(command);
        String tabId = tab.getId();
        com.runzhi.aishell.agent.AgentAuditLogger audit =
                com.runzhi.aishell.agent.AgentAuditLogger.getInstance();
        audit.record("command_proposed", tabId, risk, command, "pending");
        writeAIFeedback(tab, "[AI] 待执行命令（" + risk.label() + "）：" + command + "\n[AI] 等待用户确认...");
        if (risk == CommandRisk.LOW
                && ReadOnlyCommandWhitelist.isReadOnly(command)
                && Boolean.TRUE.equals(tab.getProperties().get("agentAutoApproveLowRisk"))) {
            publish(tab, AgentActivityBus.Kind.PROPOSAL, "[" + risk.label() + "] " + command);
            audit.record("command_auto_approved", tabId, risk, command, "approved");
            publish(tab, AgentActivityBus.Kind.EXECUTION, "低风险命令已自动批准：" + command);
            writeAIFeedback(tab, "[AI] 低风险命令已自动批准，正在执行...");
            executeAgentStep(tab, task, command);
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean decided = new java.util.concurrent.atomic.AtomicBoolean();
        AgentActivityBus.publishApproval(tabId, "[" + risk.label() + "] " + command, approved -> {
            if (!decided.compareAndSet(false, true)) return;
            if (approved) {
                audit.record("command_approved", tabId, risk, command, "approved");
                publish(tab, AgentActivityBus.Kind.EXECUTION, "用户已批准：" + command);
                writeAIFeedback(tab, "[AI] 已确认，正在执行...");
                executeAgentStep(tab, task, command);
            } else {
                audit.record("command_rejected", tabId, risk, command, "rejected");
                publish(tab, AgentActivityBus.Kind.CANCELLED, "用户拒绝执行：" + command);
                task.cancelled = true;
                activeAgentTasks.remove(tab, task);
                writeAIFeedback(tab, "[AI] 已取消执行：" + command);
            }
        });
    }

    private void executeAgentStep(Tab tab, AgentTaskState task, String command) {
        if (!isCurrentTask(tab, task)) return;
        task.commands++;
        com.runzhi.aishell.terminal.InteractiveTerminalView.writeExecutedAgentCommand(tab, command);
        publish(tab, AgentActivityBus.Kind.EXECUTION, "步骤 " + task.commands + " 正在执行：" + command);
        Future<?> request = getAICommandProcessor().executeAgentCommand(requireTabId(tab), command, output -> {
            activeAIRequests.remove(tab);
            if (!isCurrentTask(tab, task)) return;
            String safeOutput = output == null || output.isBlank() ? "（命令无输出）" : output;
            com.runzhi.aishell.terminal.InteractiveTerminalView.writeAgentExecutionOutput(tab, safeOutput);
            publish(tab, AgentActivityBus.Kind.TOOL, "步骤 " + task.commands + " 执行结果：\n" + safeOutput);
            appendExecutionContext(task, command, true, safeOutput);
            requestNextAgentStep(tab, task);
        }, error -> {
            activeAIRequests.remove(tab);
            if (!isCurrentTask(tab, task)) return;
            String message = error == null ? "未知执行错误" : error;
            publish(tab, AgentActivityBus.Kind.TOOL, "步骤 " + task.commands + " 执行失败：" + message);
            appendExecutionContext(task, command, false, message);
            requestNextAgentStep(tab, task);
        });
        activeAIRequests.put(tab, request);
    }

    private void appendExecutionContext(AgentTaskState task, String command, boolean success, String output) {
        String value = "\n\nCOMMAND_RESULT_UNTRUSTED:\ncommand=" + command + "\nsuccess=" + success
                + "\noutput:\n" + output;
        task.context += value;
        if (task.context.length() > MAX_AGENT_CONTEXT) {
            task.context = "[earlier context truncated]\n"
                    + task.context.substring(task.context.length() - MAX_AGENT_CONTEXT);
        }
    }

    private void finishAgentTask(Tab tab, AgentTaskState task, AgentActivityBus.Kind kind, String message) {
        if (!isCurrentTask(tab, task)) return;
        activeAgentTasks.remove(tab, task);
        activeAIRequests.remove(tab);
        String completedContext = task.context + "\n\nASSISTANT_RESULT:\n" + message;
        if (completedContext.length() > MAX_AGENT_CONTEXT) {
            completedContext = completedContext.substring(completedContext.length() - MAX_AGENT_CONTEXT);
        }
        agentConversationContexts.put(agentConversationKey(tab), completedContext);
        publish(tab, kind, message);
    }

    private String agentConnectionKey(Tab tab) {
        if (tab == null || tab.getId() == null) return "none";
        try {
            SSHService ssh = getSSHService();
            ConnectionParams params = ssh == null ? null : ssh.getTabConnectionParams(tab.getId());
            if (params != null && params.getHost() != null) {
                return (params.getUsername() == null ? "" : params.getUsername()) + "@"
                        + params.getHost() + ":" + params.getPort();
            }
        } catch (Exception ignored) { }
        return tab.getId();
    }

    private String agentConversationKey(Tab tab) {
        Object taskId = tab == null ? null : tab.getProperties().get("agentTaskId");
        return agentConnectionKey(tab) + "::" + (taskId == null ? "default" : taskId.toString());
    }

    /**
     * AI 向用户提问（带选项）：发布到 Agent 工作区内联渲染，用户点选后异步回调，
     * 把答案写回任务上下文并继续下一步。
     */
    private void askAgentQuestion(Tab tab, AgentTaskState task, String question, List<String> options) {
        if (!isCurrentTask(tab, task) || tab == null || tab.getId() == null) return;
        com.runzhi.aishell.agent.AgentAuditLogger.getInstance().record(
                "agent_ask", tab.getId(), com.runzhi.aishell.agent.CommandRisk.LOW, question, "asked");
        AgentActivityBus.publishAsk(tab.getId(), question, options,
                answer -> Platform.runLater(() -> onAskAnswered(tab, task, question, answer)));
    }

    private void onAskAnswered(Tab tab, AgentTaskState task, String question, String answer) {
        if (!isCurrentTask(tab, task)) return;
        if (answer == null) {
            appendUserAnswer(task, question, null);
            publish(tab, AgentActivityBus.Kind.USER, "用户未选择，Agent 继续下一步");
        } else {
            appendUserAnswer(task, question, answer);
            com.runzhi.aishell.agent.AgentAuditLogger.getInstance().record(
                    "agent_answer", tab.getId(), com.runzhi.aishell.agent.CommandRisk.LOW, question, answer);
            publish(tab, AgentActivityBus.Kind.USER, "用户选择：" + answer);
        }
        requestNextAgentStep(tab, task);
    }

    private void appendUserAnswer(AgentTaskState task, String question, String answer) {
        String value = "\n\nUSER_ANSWER:\nquestion=" + question
                + "\nanswer=" + (answer == null ? "(用户未选择)" : answer);
        task.context += value;
        if (task.context.length() > MAX_AGENT_CONTEXT) {
            task.context = "[earlier context truncated]\n"
                    + task.context.substring(task.context.length() - MAX_AGENT_CONTEXT);
        }
    }

    private void publishAgentFeedback(Tab tab, String feedback) {
        String text = feedback == null ? "未知错误" : feedback.trim();
        String lower = text.toLowerCase();
        AgentActivityBus.Kind kind;
        if (text.startsWith("[AI_THINK]")) {
            kind = AgentActivityBus.Kind.THINK;
            text = text.substring("[AI_THINK]".length()).trim();
        } else if (text.startsWith("[AI_RESPONSE]")) {
            kind = AgentActivityBus.Kind.RESPONSE;
            text = text.substring("[AI_RESPONSE]".length()).trim();
        } else if (text.startsWith("[AGENT_TOOL_RESULT]")) {
            kind = AgentActivityBus.Kind.TOOL;
            text = text.substring("[AGENT_TOOL_RESULT]".length()).trim();
            mirrorAgentToolResult(tab, text);
        } else {
            kind = lower.contains("error") || text.contains("错误") || text.contains("失败")
                    ? AgentActivityBus.Kind.ERROR : AgentActivityBus.Kind.TOOL;
        }
        publish(tab, kind, text);
        if (kind == AgentActivityBus.Kind.ERROR) {
            AgentTaskState failed = activeAgentTasks.remove(tab);
            if (failed != null) failed.cancelled = true;
            activeAIRequests.remove(tab);
        }
        String prefix = switch (kind) {
            case THINK -> "[AI 思考] ";
            case RESPONSE -> "[AI 响应] ";
            case ERROR -> "[AI] 错误：";
            default -> "[AI] ";
        };
        writeAIFeedback(tab, prefix + text);
    }

    private void mirrorAgentToolResult(Tab tab, String payload) {
        int newline = payload.indexOf('\n');
        String tool = newline < 0 ? payload : payload.substring(0, newline).trim();
        String output = newline < 0 ? "" : payload.substring(newline + 1).trim();
        String[] commands = switch (tool) {
            case "system_info" -> new String[]{"hostname", "last -n 1"};
            case "disk_usage" -> new String[]{"LC_ALL=C df -hP"};
            case "memory_usage" -> new String[]{"LC_ALL=C free -h 2>/dev/null || vm_stat"};
            case "process_snapshot" -> new String[]{"LC_ALL=C ps -eo pid,ppid,user,%cpu,%mem,stat,comm --sort=-%cpu | head -n 31"};
            case "network_listeners" -> new String[]{"LC_ALL=C ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null"};
            default -> new String[0];
        };
        for (String command : commands) {
            com.runzhi.aishell.terminal.InteractiveTerminalView.writeExecutedAgentCommand(tab, command);
        }
        if (commands.length == 0) {
            String operation = switch (tool) {
                case "connection_status" -> "读取当前连接状态（无 Shell 命令）";
                case "working_directory" -> "读取已同步工作目录（无 Shell 命令）";
                case "list_remote_files" -> "通过 SFTP 读取远程目录（无 Shell 命令）";
                case "read_remote_text_file" -> "通过 SFTP 读取远程文件（无 Shell 命令）";
                case "execute_command" -> "通过会话执行命令（沙箱校验）";
                case "write_remote_text_file" -> "通过 SFTP 写入远程文本文件";
                case "create_remote_directory" -> "通过 SFTP 创建远程目录";
                case "delete_remote_path" -> "通过 SFTP 删除远程路径";
                case "rename_remote_path" -> "通过 SFTP 重命名远程路径";
                case "remote_path_exists" -> "检查远程路径是否存在";
                default -> "Agent 工具：" + tool;
            };
            com.runzhi.aishell.terminal.InteractiveTerminalView.writeAgentExecutionOutput(tab, "[Agent 工具] " + operation);
        }
        com.runzhi.aishell.terminal.InteractiveTerminalView.writeAgentExecutionOutput(tab, output);
    }

    private void publish(Tab tab, AgentActivityBus.Kind kind, String text) {
        if (tab != null && tab.getId() != null) AgentActivityBus.publish(tab.getId(), kind, text);
    }

    private void writeAIFeedback(Tab tab, String message) {
        // AI 内容统一由 AgentActivityBus 投递到右侧 Agent 工作区。
        // 终端缓冲区必须只包含真实远程 Shell I/O，避免反馈文本破坏光标和屏幕状态。
        log.trace("AI feedback routed outside terminal for tab {}: {}",
                tab == null ? null : tab.getId(), message);
    }
    
    private void handleProcessedCommand(Tab tab, String command, boolean isCdCommand) {
        if (tab == null) return;
        if (tab.getProperties().containsKey("interactiveTerminalWidget")) {
            // 真终端应直接执行 cd，让远端 Shell 自己维护工作目录和提示符。
            executeCommandInTab(tab, command);
        } else if (isCdCommand) {
            handleCdCommandForTab(tab, command);
        } else {
            executeCommandInTab(tab, command);
        }
    }

    @Override
    public void executeCommandInTab(Tab tab, String command) {
        log.debug("Executing command in tab {}: {}", tab != null ? tab.getText() : "null", command);

        // 获取 SSH 服务
        SSHService sshService = getSSHService();
        if (sshService == null) {
            log.warn("SSH service not available");
            return;
        }
        
        // 获取 Tab ID 并使用 Tab 特定的连接发送命令
        String tabId = tab != null ? tab.getId() : null;
        if (tabId != null) {
            Object interactiveWidget = tab.getProperties().get("interactiveTerminalWidget");
            if (interactiveWidget instanceof com.techsenger.jeditermfx.ui.JediTermFxWidget widget
                    && widget.getTtyConnector() != null
                    && widget.getTtyConnector().isConnected()) {
                try {
                    widget.getTtyConnector().write(command + "\r");
                    log.debug("Command sent to interactive terminal: {}", tabId);
                } catch (Exception e) {
                    log.error("发送命令到交互终端失败：{}", e.getMessage(), e);
                    appendConnectionMessage(tab, "命令发送失败：" + e.getMessage() + "\n");
                }
                return;
            }
            com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
            if (connection != null && connection.isConnected()) {
                try {
                    connection.sendCommand(command);
                    log.debug("Command sent to tab connection: {}", tabId);
                } catch (Exception e) {
                    log.error("发送命令失败：{}", e.getMessage());
                    appendConnectionMessage(tab, "命令发送失败：" + e.getMessage() + "\n");
                }
            } else {
                // 绝不能把命令发送到另一个 Tab 的连接。
                log.warn("Tab {} SSH connection is not available", tabId);
                appendConnectionMessage(tab, "当前终端未连接，请先重连。\n");
            }
        } else {
            // 没有 tabId 的命令一律不发送：无 tab 作用域就无法保证不串线到别的连接。
            log.warn("Command rejected: tab has no id, refusing to use a global connection");
            appendConnectionMessage(tab, "无法确定目标终端，命令未发送。\n");
        }
    }

    private void appendConnectionMessage(Tab tab, String message) {
        InlineCssTextArea output = getOutputAreaForTab(tab);
        if (output != null) {
            Platform.runLater(() -> output.appendText(message));
        }
    }

    @Override
    public void handleCdCommandForTab(Tab tab, String command) {
        if (tab == null || command == null) return;
        
        // 获取 SSH 服务
        SSHService sshService = getSSHService();
        if (sshService != null && sshService.isConnected()) {
            // 使用 SSH 服务处理 cd 命令
            String tabId = tab.getId();
            if (tabId != null) {
                sshService.handleCdCommandForTab(tabId, command);
                
                // 同步远程目录到本地状态
                sshService.syncRemoteDirectoryForTab(tabId);
                // 更新 Tab 的本地目录跟踪
                String currentDir = sshService.getCurrentWorkingDirectoryForTab(tabId);
                if (currentDir != null) {
                    tabCurrentWorkingDirectories.put(tab, currentDir);
                }
            }
        }
    }

    @Override
    public void addCommandToHistory(Tab tab, String command) {
        commandHistoryHandler.addCommandToHistory(tab, command);
    }

    @Override
    public void setupOutputAreaCommandInput(InlineCssTextArea outputArea, String tabId, Tab tab) {
        log.debug("Setting up output area command input for tab: {}", tabId);
        // 实现在输出区域输入命令的功能
        if (outputArea == null || tab == null) {
            log.warn("Output area or tab is null, cannot setup command input");
            return;
        }
        
        try {
            // 设置输出区域为可编辑，允许直接在输出区域输入命令
            outputArea.setEditable(true);
            
            // 监听键盘事件以执行命令
            outputArea.setOnKeyPressed(event -> {
                if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    event.consume();
                    
                    // 获取整个文本内容
                    String fullText = outputArea.getText();
                    int caretPos = outputArea.getCaretPosition();
                    
                    // 找到光标所在行的开始位置
                    int lineStart = fullText.lastIndexOf('\n', caretPos - 1);
                    if (lineStart == -1) lineStart = 0;
                    else lineStart++; // 跳过换行符
                    
                    // 获取当前行的文本（从提示符后开始）
                    String currentLine = fullText.substring(lineStart, caretPos);
                    
                    // 提取命令 - 移除提示符
                    String command = extractCommandFromLine(currentLine);
                    
                    if (command != null && !command.isEmpty()) {
                        log.debug("Executing command from terminal output area: {}", command);
                        
                        // 添加到命令历史
                        commandHistoryHandler.addCommandToHistory(tab, command);
                        
                        // 执行命令（发送到 SSH 服务器）
                        // 注意：不在本地添加换行，让 SSH 服务器的回显来处理
                        executeCommandInTab(tab, command);
                    }
                } else if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
                    // 防止删除提示符之前的内容
                    String fullText = outputArea.getText();
                    int caretPos = outputArea.getCaretPosition();
                    
                    // 找到当前行的开始位置
                    int lineStart = fullText.lastIndexOf('\n', caretPos - 1);
                    if (lineStart == -1) lineStart = 0;
                    else lineStart++;
                    
                    // 获取当前行提示符后的位置
                    String currentLine = fullText.substring(lineStart);
                    int promptEnd = findPromptEnd(currentLine);
                    
                    // 如果光标在提示符位置，阻止删除
                    if (caretPos <= lineStart + promptEnd) {
                        event.consume();
                    }
                }
            });
            
            log.debug("Output area command input setup completed for tab: {}", tabId);
        } catch (Exception e) {
            log.error("Failed to setup output area command input: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从一行文本中提取命令（移除提示符）
     */
    private String extractCommandFromLine(String line) {
        if (line == null || line.isEmpty()) return "";
        
        // 常见的提示符模式: [user@host path]$ 或 user@host:~$ 或 [root@hostname ~]#
        // 查找最后一个 $ 或 # 后面的内容
        int lastDollar = line.lastIndexOf('$');
        int lastHash = line.lastIndexOf('#');
        int promptEnd = Math.max(lastDollar, lastHash);
        
        if (promptEnd >= 0 && promptEnd < line.length() - 1) {
            return line.substring(promptEnd + 1).trim();
        }
        
        // 如果没有找到提示符，返回整行
        return line.trim();
    }
    
    /**
     * 找到提示符结束位置
     */
    private int findPromptEnd(String line) {
        if (line == null || line.isEmpty()) return 0;
        
        int lastDollar = line.indexOf('$');
        int lastHash = line.indexOf('#');
        int promptEnd = Math.max(lastDollar, lastHash);
        
        return promptEnd >= 0 ? promptEnd + 1 : 0;
    }

    @Override
    public String getCurrentWorkingDirectory(Tab tab) {
        if (tab == null) return System.getProperty("user.dir");

        String currentDir = tabCurrentWorkingDirectories.get(tab);

        if (currentDir == null) {
            // 如果没有存储的目录，使用默认值
            currentDir = System.getProperty("user.home");
            tabCurrentWorkingDirectories.put(tab, currentDir);
        }

        return currentDir;
    }

    @Override
    public void executeShellCommand(Tab tab, String command) {
        if (tab == null || command == null || command.trim().isEmpty()) return;

        String trimmedCommand = command.trim();

        // 检查是否为本地命令
        if (isLocalShellCommand(trimmedCommand)) {
            executeLocalShellCommand(tab, trimmedCommand);
        } else {
            // 发送到远程 SSH 连接
            // 注意：不在本地显示命令，让 SSH 服务器的回显来处理
            executeCommandInTab(tab, trimmedCommand);
        }
    }
    
    private boolean isLocalShellCommand(String command) {
        // 检查是否为本地 shell 命令 - 只有真正的本地命令才在这里处理
        // 其他命令都应该发送到远程 SSH 服务器
        String[] localCommands = {"help", "clear", "cls", "history"};
        String cmd = command.split("\\s+")[0].toLowerCase();
        for (String localCmd : localCommands) {
            if (localCmd.equals(cmd)) {
                return true;
            }
        }
        return false;
    }
    
    private void executeLocalShellCommand(Tab tab, String command) {
        if (tab == null || command == null) return;

        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea == null) return;

        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "help":
                showLocalHelp(outputArea);
                break;
            case "clear":
            case "cls":
                Platform.runLater(outputArea::clear);
                break;
            case "history":
                showCommandHistory(outputArea, tab);
                break;
            default:
                Platform.runLater(() -> outputArea.appendText("未知的本地命令：" + cmd + "\n"));
                break;
        }
    }
    
    private void showLocalHelp(InlineCssTextArea outputArea) {
        Platform.runLater(() -> {
            outputArea.appendText("本地命令列表:\n");
            outputArea.appendText("  help      - 显示此帮助信息\n");
            outputArea.appendText("  clear/cls - 清空终端屏幕\n");
            outputArea.appendText("  history   - 显示命令历史\n");
            outputArea.appendText("其他命令将发送到远程 SSH 服务器执行\n");
        });
    }
    
    private void showCommandHistory(InlineCssTextArea outputArea, Tab tab) {
        List<String> history = commandHistoryHandler.getHistoryForTab(tab);
        Platform.runLater(() -> {
            outputArea.appendText("命令历史:\n");
            for (int i = 0; i < history.size(); i++) {
                outputArea.appendText(String.format("%3d  %s\n", i + 1, history.get(i)));
            }
        });
    }
    
    /**
     * 获取 Tab 对应的输出区域（辅助方法）
     */
    private InlineCssTextArea getOutputAreaForTab(Tab tab) {
        if (tab == null) return null;
        try {
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalTabService tabService = moduleManager.getTerminalTabService();
                if (tabService instanceof TerminalTabServiceImpl) {
                    return ((TerminalTabServiceImpl) tabService).getTabToOutput().get(tab);
                }
            }
        } catch (Exception e) {
            log.warn("无法获取 Tab 的输出区域：{}", e.getMessage());
        }
        return null;
    }

    @Override
    public void enableAdvancedShellFeatures(Tab tab) {
        if (tab == null) return;

        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            String tabId = getTabId(tab);
            // 使用新的 TerminalEnhancementManager 启用高级 shell 功能
            TerminalEnhancementManager enhancementManager = TerminalEnhancementManager.getInstance();
            enhancementManager.enableAllEnhancements(outputArea, tabId);
        }
    }

    @Override
    public void setupAdvancedKeyboardShortcuts(Tab tab) {
        if (tab == null) return;

        InlineCssTextArea outputArea = getOutputAreaForTab(tab);
        if (outputArea != null) {
            String tabId = getTabId(tab);
            // 使用新的 TerminalEnhancementManager 启用高级键盘快捷键
            TerminalEnhancementManager enhancementManager = TerminalEnhancementManager.getInstance();
            enhancementManager.enableKeyboardShortcuts(outputArea, tabId);
        }
    }

    @Override
    public void attemptReconnection(Tab tab) {
        log.info("Attempting reconnection for tab: {}", tab != null ? tab.getText() : "null");
        // 实现重连逻辑
        if (tab == null) {
            log.warn("Tab is null, cannot attempt reconnection");
            return;
        }
        
        try {
            com.runzhi.aishell.common.ai.AIShellModuleManager moduleManager = 
                com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance();
            if (moduleManager != null) {
                TerminalConnectionService connectionService = moduleManager.getTerminalConnectionService();
                if (connectionService != null) {
                    connectionService.attemptReconnection(tab);
                    log.info("Delegated reconnection to TerminalConnectionService");
                }
            }
        } catch (Exception e) {
            log.error("Error during reconnection attempt: {}", e.getMessage(), e);
        }
    }

    @Override
    public void pasteToTerminal(Tab tab) {
        log.info("Pasting to terminal: {}", tab != null ? tab.getText() : "null");
        // 实现粘贴功能
        if (tab == null) {
            log.warn("Tab is null, cannot paste");
            return;
        }
        
        try {
            InlineCssTextArea outputArea = getOutputAreaForTab(tab);
            if (outputArea != null) {
                Platform.runLater(() -> {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    if (clipboard.hasString()) {
                        String content = clipboard.getString();
                        if (content != null) {
                            outputArea.appendText(content);
                            log.debug("Successfully pasted content to tab: {}", tab.getText());
                        }
                    }
                });
            } else {
                log.warn("Output area not found for tab: {}", tab.getText());
            }
        } catch (Exception e) {
            log.error("Failed to paste to terminal: {}", e.getMessage(), e);
        }
    }

    @Override
    public void clearScreen(Tab tab) {
        log.debug("Clearing screen for tab: {}", tab != null ? tab.getText() : "null");
        // 实现清屏功能
        if (tab == null) {
            log.warn("Tab is null, cannot clear screen");
            return;
        }
        
        try {
            InlineCssTextArea outputArea = getOutputAreaForTab(tab);
            if (outputArea != null) {
                String tabId = getTabId(tab);
                
                // 发送 clear 命令到远程服务器
                SSHService sshService = getSSHService();
                if (sshService != null) {
                    com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
                    if (connection != null && connection.isConnected()) {
                        connection.sendCommand("clear\n");
                    }
                }
                
                // 清空本地输出区域
                Platform.runLater(() -> {
                    outputArea.clear();
                    outputArea.appendText("屏幕已清空\n");
                    log.debug("Screen cleared for tab: {}", tab.getText());
                });
            } else {
                log.warn("Output area not found for tab: {}", tab.getText());
            }
        } catch (Exception e) {
            log.error("Failed to clear screen: {}", e.getMessage(), e);
        }
    }

    @Override
    public void setupCommandHistoryNavigation(TextField commandInput, Tab tab) {
        log.debug("Setting up command history navigation");
        commandHistoryHandler.setupCommandHistoryNavigation(commandInput, tab);
    }

    @Override
    public void setupTabShortcuts(Tab tab, TextField input) {
        log.debug("Setting up tab shortcuts for tab: {}", tab != null ? tab.getText() : "null");
        if (input != null) {
            input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == javafx.scene.input.KeyCode.UP || event.getCode() == javafx.scene.input.KeyCode.DOWN) {
                    // 已在 setupCommandHistoryNavigation 中处理
                    return;
                }

                // Tab 键补全（简单实现）
                if (event.getCode() == javafx.scene.input.KeyCode.TAB) {
                    String currentText = input.getText();
                    if (currentText != null && !currentText.isEmpty()) {
                        autoCompleteCommand(input, currentText);
                    }
                    event.consume();
                }
            });
        }
    }

    /**
     * 自动补全命令（简单实现）
     */
    private void autoCompleteCommand(TextField input, String currentText) {
        // 检查是否是路径补全（以/开头或包含/）
        if (currentText.contains("/")) {
            // 这里可以调用SSH 服务来获取远程路径补全
            // 为简化，我们只做基本的路径补全
            autoCompletePath(input, currentText);
        } else {
            // 执行命令补全
            autoCompleteCommandInternal(input, currentText);
        }
    }
    
    /**
     * 自动补全路径
     */
    private void autoCompletePath(TextField input, String currentText) {
        // 路径补全逻辑
        // 这里可以调用SSH 服务来获取远程路径补全
        // 为简化，我们只做基本的路径补全
        SSHService sshService = getSSHService();
        if (sshService != null) {
            // 修复：从输入框获取所属的 Tab，而不是强制类型转换
            javafx.scene.control.Tab tab = null;
            Parent parent = input.getParent();
            while (parent != null) {
                if (parent instanceof TabPane) {
                    TabPane tabPane = (TabPane) parent;
                    tab = tabPane.getSelectionModel().getSelectedItem();
                    break;
                }
                parent = parent.getParent();
            }

            String tabId = getTabId(tab);
            if (tabId != null) {
                com.runzhi.aishell.ssh.SSHConnectionManager connection = sshService.getTabConnection(tabId);
                if (connection != null && connection.isConnected()) {
                    // 发送命令获取路径补全建议
                    // 为简化，这里只做基本的补全
                    String pathPrefix = currentText;

                    // 查找最后一个/的位置
                    int lastSlash = pathPrefix.lastIndexOf('/');
                    String dirPath = lastSlash >= 0 ? pathPrefix.substring(0, lastSlash + 1) : "./";
                    String filePrefix = lastSlash >= 0 ? pathPrefix.substring(lastSlash + 1) : pathPrefix;

                    // 尝试补全路径（走 SFTP 列表，不拼接 shell 命令，杜绝注入）
                    String completedPath = autoCompleteRemotePath(sshService, tabId, dirPath, filePrefix);
                    if (completedPath != null && !completedPath.equals(currentText)) {
                        input.setText(completedPath);
                        input.positionCaret(completedPath.length());
                    }
                }
            }
        }
    }
    
    /**
     * 自动补全远程路径
     */
    /**
     * 自动补全远程路径：通过 SFTP 列举目录并在本地过滤。
     * 不拼接 ls/grep/awk 等 shell 命令，避免路径中的元字符注入远程 shell（2026-10-23 审计 P0-4）。
     */
    private String autoCompleteRemotePath(SSHService sshService, String tabId, String dirPath, String filePrefix) {
        try {
            java.util.List<com.runzhi.aishell.ssh.domain.FileInfo> entries = sshService.listFilesForTab(tabId, dirPath);
            if (entries == null || entries.isEmpty()) return null;

            java.util.List<String> matches = new java.util.ArrayList<>();
            for (com.runzhi.aishell.ssh.domain.FileInfo entry : entries) {
                String name = entry.getName();
                if (name == null || name.equals(".") || name.equals("..")) continue;
                if (name.startsWith(filePrefix)) matches.add(name);
            }
            if (matches.isEmpty()) return null;
            if (matches.size() == 1) return dirPath + matches.get(0);
            String commonPrefix = findLongestCommonPrefix(matches.toArray(new String[0]));
            return (commonPrefix != null && !commonPrefix.isEmpty()) ? dirPath + commonPrefix : null;
        } catch (Exception e) {
            log.warn("无法获取远程路径补全：{}", e.getMessage());
        }
        return null;
    }
    
    private String findLongestCommonPrefix(String... strings) {
        if (strings == null || strings.length == 0) return "";
        if (strings.length == 1) return strings[0];

        String prefix = strings[0];
        for (int i = 1; i < strings.length; i++) {
            prefix = findLongestCommonPrefix(prefix, strings[i]);
        }
        return prefix;
    }
    
    /**
     * 查找两个字符串的最长公共前缀
     */
    private String findLongestCommonPrefix(String str1, String str2) {
        if (str1 == null || str2 == null) return "";
        int minLength = Math.min(str1.length(), str2.length());
        int i = 0;
        while (i < minLength && str1.charAt(i) == str2.charAt(i)) {
            i++;
        }
        return str1.substring(0, i);
    }
    
    /**
     * 获取 Tab ID（辅助方法）
     */
    private String getTabId(Tab tab) {
        if (tab == null) return null;
        
        // 尝试从 Tab 的属性中获取 ID
        if (tab.getId() != null) {
            return tab.getId();
        }
        
        // 尝试从 Tab 的 userData 中获取 ID
        if (tab.getUserData() != null) {
            return String.valueOf(tab.getUserData());
        }
        
        // 如果都没有，使用 Tab 的文本作为 ID
        return tab.getText() != null ? tab.getText() : String.valueOf(System.identityHashCode(tab));
    }
    
    /**
     * 预定义的常用命令集合，用于快速查找
     */
    private static final Set<String> COMMON_COMMANDS = new HashSet<>(Arrays.asList(
            "ls", "cd", "pwd", "cat", "grep", "find", "cp", "mv", "rm", "mkdir",
            "rmdir", "touch", "chmod", "chown", "ps", "top", "htop", "kill",
            "ping", "wget", "curl", "scp", "ssh", "df", "du", "free", "uname",
            "whoami", "date", "cal", "man", "history", "echo", "export", "alias",
            "clear", "less", "more", "head", "tail", "sort", "uniq", "wc", "cut",
            "paste", "join", "diff", "cmp", "comm", "tr", "sed", "awk", "tar",
            "gzip", "gunzip", "zip", "unzip", "which", "whereis", "locate", "find",
            "ifconfig", "netstat", "route", "nslookup", "dig", "traceroute", "telnet",
            "ftp", "sftp", "rsync", "screen", "tmux", "nano", "vim", "vi", "emacs",
            "env", "printenv", "crontab", "at", "jobs", "fg", "bg", "nohup", "nice",
            "renice", "uptime", "w", "last", "login", "logout", "exit", "su", "sudo",
            "passwd", "groupadd", "useradd", "userdel", "usermod", "groupmod", "chgrp",
            "chfn", "chsh", "mount", "umount", "fsck", "mkfs", "fdisk", "parted",
            "lsblk", "blkid", "lsof", "fuser", "killall", "pkill", "pgrep", "pidof",
            "git", "docker", "kubectl", "npm", "node", "python", "pip", "make", "gcc"
    ));
    
    /**
     * 自动补全命令内部实现
     */
    private void autoCompleteCommandInternal(TextField input, String currentText) {
        // 查找匹配的命令
        String bestMatch = null;
        for (String cmd : COMMON_COMMANDS) {
            if (cmd.startsWith(currentText)) {
                if (bestMatch == null) {
                    bestMatch = cmd;
                } else {
                    // 如果找到多个匹配项，使用最长公共前缀
                    bestMatch = findLongestCommonPrefix(bestMatch, cmd);
                }
            }
        }

        if (bestMatch != null) {
            input.setText(bestMatch);
            input.positionCaret(bestMatch.length());

            // 如果只有一个完全匹配的命令，添加空格
            boolean exactMatch = COMMON_COMMANDS.contains(bestMatch);

            if (exactMatch) {
                // 可以选择是否自动添加空格
                // input.appendText(" ");
            }
        }
    }
}
