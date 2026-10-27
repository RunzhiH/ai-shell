/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.service.TerminalCommandService;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Claude-style Agent workspace bound to the active SSH tab. */
public class AgentWorkspaceController {
    private static final String AUTO_LOW_RISK = "agentAutoApproveLowRisk";
    private static final String AGENT_TASK_ID = "agentTaskId";
    private static final String RESET = "\u001b[0m";
    private static final List<AgentConsoleView.Command> COMMANDS = List.of(
            new AgentConsoleView.Command("/help", "显示全部命令"),
            new AgentConsoleView.Command("/new", "新建独立上下文任务"),
            new AgentConsoleView.Command("/tasks", "列出当前连接的任务"),
            new AgentConsoleView.Command("/resume", "恢复指定任务"),
            new AgentConsoleView.Command("/continue", "继续最近任务"),
            new AgentConsoleView.Command("/model", "查看或切换模型"),
            new AgentConsoleView.Command("/context", "显示当前上下文"),
            new AgentConsoleView.Command("/permissions", "显示执行权限策略"),
            new AgentConsoleView.Command("/auto", "切换低风险自动批准"),
            new AgentConsoleView.Command("/stop", "停止当前 Agent"),
            new AgentConsoleView.Command("/clear", "清空当前任务输出"));

    @FXML private StackPane terminalHost;
    private final Map<String, List<AgentActivityBus.Event>> history = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> sessionHistoryKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<AgentConversation>> connectionTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AgentActivityBus.Event> pendingQuestions = new java.util.concurrent.ConcurrentHashMap<>();
    private Supplier<Tab> activeTabSupplier = () -> null;
    private Consumer<String> commandInserter = ignored -> { };
    private TerminalCommandService commandService;
    private AgentConsoleView console;
    private boolean agentRunning;

    @FXML
    private void initialize() {
        console = new AgentConsoleView(COMMANDS,
                line -> Platform.runLater(() -> handleLine(line)),
                () -> Platform.runLater(this::stopAgent),
                () -> Platform.runLater(this::clearCurrent));
        terminalHost.getChildren().add(console);
        console.welcome();
        AgentActivityBus.subscribe(this::receive);
        Platform.runLater(console::requestInputFocus);
    }

    public void configure(Supplier<Tab> activeTabSupplier, TerminalCommandService commandService,
                          Consumer<String> commandInserter) {
        this.activeTabSupplier = activeTabSupplier == null ? () -> null : activeTabSupplier;
        this.commandService = commandService;
        this.commandInserter = commandInserter == null ? ignored -> { } : commandInserter;
        showTab(activeTab());
    }

    public void showTab(Tab tab) {
        if (tab != null && tab.getId() != null) {
            configureTasks(tab);
            sessionHistoryKeys.put(tab.getId(), historyKey(tab));
        }
        render(tab);
    }

    private void handleLine(String line) {
        Tab tab = activeTab();
        if (tab == null || tab.getId() == null) { status("未选择已连接的终端"); return; }
        if (line.equalsIgnoreCase("/stop") || line.equalsIgnoreCase("/clear")) {
            handleCommand(line);
            return;
        }
        AgentActivityBus.Event question = pendingQuestions.remove(tab.getId());
        if (question != null && question.answerHandler() != null) {
            String answer = resolveAnswer(question, line);
            console.print(color("56d364") + "回答  " + escape(answer) + RESET);
            question.answerHandler().accept(answer);
            return;
        }
        if (line.startsWith("/")) { handleCommand(line); return; }
        if (agentRunning) { status("当前任务仍在运行；按 Ctrl+C 或输入 /stop 停止"); return; }
        if (commandService == null) { status("Agent 服务尚未初始化"); return; }
        commandService.processAITerminalCommand(tab, line);
        agentRunning = true;
    }

    private String resolveAnswer(AgentActivityBus.Event question, String line) {
        String value = line == null ? "" : line.trim();
        if (value.matches("[0-9]+")) {
            int index = Integer.parseInt(value) - 1;
            if (index >= 0 && index < question.options().size()) return question.options().get(index);
        }
        return value;
    }

    private void handleCommand(String input) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length == 2 ? parts[1].trim() : "";
        Tab tab = activeTab();
        switch (command) {
            case "/help" -> status("命令\r\n  /new [名称]      新任务\r\n  /tasks           任务列表\r\n"
                    + "  /resume N        恢复任务\r\n  /continue        最近任务\r\n"
                    + "  /model [N|名称]  模型\r\n  /context         上下文\r\n"
                    + "  /permissions     权限策略\r\n  /auto on|off     低风险自动批准\r\n"
                    + "  /stop            停止\r\n  /clear           清屏");
            case "/new" -> { if (!rejectRunning("新建任务")) newTask(argument); }
            case "/tasks" -> status(taskList(tab));
            case "/resume" -> { if (!rejectRunning("切换任务")) switchTaskByArgument(tab, argument); }
            case "/continue" -> { if (!rejectRunning("切换任务")) continueLatest(tab); }
            case "/model" -> { if (argument.isBlank()) status(modelList()); else if (!rejectRunning("切换模型")) switchModel(argument); }
            case "/context" -> status(contextSummary(tab));
            case "/permissions" -> status(permissionSummary(tab));
            case "/auto" -> changeAuto(tab, argument);
            case "/stop" -> stopAgent();
            case "/clear" -> clearCurrent();
            default -> error("未知命令：" + command + "；输入 / 查看命令");
        }
    }

    private boolean rejectRunning(String operation) {
        if (!agentRunning) return false;
        status("任务运行中，不能" + operation + "；请先 /stop");
        return true;
    }

    private void configureTasks(Tab tab) {
        List<AgentConversation> tasks = connectionTasks.computeIfAbsent(connectionKey(tab), ignored -> new ArrayList<>());
        if (tasks.isEmpty()) tasks.add(new AgentConversation(UUID.randomUUID().toString(), "任务 1"));
        String selectedId = (String) tab.getProperties().get(AGENT_TASK_ID);
        AgentConversation selected = tasks.stream().filter(task -> task.id().equals(selectedId)).findFirst().orElse(tasks.get(0));
        tab.getProperties().put(AGENT_TASK_ID, selected.id());
    }

    private void newTask(String requestedTitle) {
        Tab tab = activeTab();
        if (tab == null) return;
        List<AgentConversation> tasks = connectionTasks.computeIfAbsent(connectionKey(tab), ignored -> new ArrayList<>());
        String title = requestedTitle.isBlank() ? "任务 " + (tasks.size() + 1) : requestedTitle;
        AgentConversation task = new AgentConversation(UUID.randomUUID().toString(), title);
        tasks.add(task);
        switchTask(tab, task);
        status("已创建任务：" + title);
    }

    private void switchTask(Tab tab, AgentConversation task) {
        tab.getProperties().put(AGENT_TASK_ID, task.id());
        sessionHistoryKeys.put(tab.getId(), historyKey(tab));
        render(tab);
    }

    private void switchTaskByArgument(Tab tab, String argument) {
        List<AgentConversation> tasks = tab == null ? List.of() : connectionTasks.getOrDefault(connectionKey(tab), List.of());
        try {
            int index = Integer.parseInt(argument) - 1;
            if (index < 0 || index >= tasks.size()) throw new NumberFormatException();
            switchTask(tab, tasks.get(index));
            status("已恢复任务：" + tasks.get(index).title());
        } catch (NumberFormatException e) { error("无效任务编号；使用 /tasks 查看"); }
    }

    private void continueLatest(Tab tab) {
        List<AgentConversation> tasks = tab == null ? List.of() : connectionTasks.getOrDefault(connectionKey(tab), List.of());
        if (tasks.isEmpty()) { error("没有可恢复的任务"); return; }
        switchTask(tab, tasks.get(tasks.size() - 1));
        status("已继续任务：" + tasks.get(tasks.size() - 1).title());
    }

    private String taskList(Tab tab) {
        if (tab == null) return "未选择终端";
        List<AgentConversation> tasks = connectionTasks.getOrDefault(connectionKey(tab), List.of());
        String active = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        StringBuilder result = new StringBuilder("任务列表");
        for (int i = 0; i < tasks.size(); i++) result.append("\r\n ")
                .append(tasks.get(i).id().equals(active) ? "*" : " ").append(' ')
                .append(i + 1).append("  ").append(tasks.get(i).title());
        return result.toString();
    }

    private String modelList() {
        List<AIConfig> configs = AIConfigManager.getInstance().getAllConfigs();
        AIConfig current = AIConfigManager.getInstance().getCurrentConfig();
        StringBuilder result = new StringBuilder("模型列表");
        for (int i = 0; i < configs.size(); i++) {
            AIConfig config = configs.get(i);
            result.append("\r\n ").append(current != null && config.getName().equals(current.getName()) ? "*" : " ")
                    .append(' ').append(i + 1).append("  ").append(config.getName()).append(" · ").append(config.getModel());
        }
        return result.toString();
    }

    private void switchModel(String argument) {
        List<AIConfig> configs = AIConfigManager.getInstance().getAllConfigs();
        AIConfig selected = null;
        try { int index = Integer.parseInt(argument) - 1; if (index >= 0 && index < configs.size()) selected = configs.get(index); }
        catch (NumberFormatException ignored) {
            selected = configs.stream().filter(config -> config.getName().equalsIgnoreCase(argument)
                    || config.getModel().equalsIgnoreCase(argument)).findFirst().orElse(null);
        }
        if (selected == null) { error("未找到模型；使用 /model 查看"); return; }
        AIConfigManager.getInstance().setCurrentConfig(selected.getName());
        updateTerminalStatus(activeTab());
        audit("model_switched", "model: " + selected.getName() + " · " + selected.getModel(), "switched");
        status("已切换模型：" + selected.getName() + " · " + selected.getModel());
    }

    private void changeAuto(Tab tab, String argument) {
        if (tab == null || !(argument.equalsIgnoreCase("on") || argument.equalsIgnoreCase("off"))) { error("用法：/auto on|off"); return; }
        boolean enabled = argument.equalsIgnoreCase("on");
        tab.getProperties().put(AUTO_LOW_RISK, enabled);
        audit("auto_approve_changed", "/auto " + argument, enabled ? "on" : "off");
        status("低风险命令自动批准：" + (enabled ? "开启" : "关闭"));
    }

    /** 控制动作（开关、切换）也入审计，保证责任链完整。 */
    private void audit(String event, String subject, String result) {
        Tab tab = activeTab();
        com.runzhi.aishell.agent.AgentAuditLogger.getInstance().record(
                event, tab == null || tab.getId() == null ? "unknown" : tab.getId(),
                com.runzhi.aishell.agent.CommandRisk.LOW, subject, result);
    }

    private String contextSummary(Tab tab) {
        if (tab == null) return "未选择终端";
        String taskId = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        String task = connectionTasks.getOrDefault(connectionKey(tab), List.of()).stream()
                .filter(item -> item.id().equals(taskId)).map(AgentConversation::title).findFirst().orElse("-");
        AIConfig model = AIConfigManager.getInstance().getCurrentConfig();
        return "连接  " + connectionKey(tab) + "\r\n任务  " + task + "\r\n模型  "
                + (model == null ? "未配置" : model.getName() + " · " + model.getModel()) + "\r\n记录  "
                + history.getOrDefault(historyKey(tab), List.of()).size() + " 条";
    }

    private String permissionSummary(Tab tab) {
        boolean auto = tab != null && Boolean.TRUE.equals(tab.getProperties().get(AUTO_LOW_RISK));
        return "只读检查工具  允许\r\n低风险命令    " + (auto ? "自动批准" : "执行前确认")
                + "\r\n中风险命令    执行前确认\r\n高风险命令    二次确认\r\n沙箱          30 秒 / 1 MiB";
    }

    private void receive(AgentActivityBus.Event event) {
        Platform.runLater(() -> {
            String key = sessionHistoryKeys.getOrDefault(event.sessionId(), event.sessionId());
            List<AgentActivityBus.Event> events = history.computeIfAbsent(key, ignored -> new ArrayList<>());
            events.add(event);
            if (events.size() > 300) events.subList(0, events.size() - 300).clear();
            Tab tab = activeTab();
            if (tab == null || !event.sessionId().equals(tab.getId())) return;
            if (event.kind() != AgentActivityBus.Kind.USER) printEvent(event);
            if (event.kind() == AgentActivityBus.Kind.ASK && event.answerHandler() != null) pendingQuestions.put(event.sessionId(), event);
            if (event.kind() == AgentActivityBus.Kind.SUCCESS || event.kind() == AgentActivityBus.Kind.ERROR
                    || event.kind() == AgentActivityBus.Kind.CANCELLED) agentRunning = false;
        });
    }

    private void printEvent(AgentActivityBus.Event event) {
        if (event.kind() == AgentActivityBus.Kind.PROPOSAL && event.approvalHandler() != null) {
            String proposal = event.text() == null ? "" : event.text().trim();
            int endRisk = proposal.startsWith("[") ? proposal.indexOf(']') : -1;
            String risk = endRisk > 1 ? proposal.substring(1, endRisk) : "待确认";
            String command = endRisk > 1 ? proposal.substring(endRisk + 1).trim() : proposal;
            console.printCommandProposal(command, risk, event.approvalHandler(), commandInserter);
            return;
        }
        console.print(formatEvent(event));
        if (event.kind() == AgentActivityBus.Kind.ASK && !event.options().isEmpty()) {
            StringBuilder options = new StringBuilder();
            for (int i = 0; i < event.options().size(); i++) options.append(i == 0 ? "" : "\r\n")
                    .append("  ").append(i + 1).append(") ").append(event.options().get(i));
            console.print(color("ffd866") + options + RESET);
        }
    }

    private void render(Tab tab) {
        updateTerminalStatus(tab);
        console.welcome();
        if (tab == null || tab.getId() == null) { status("请选择一个已连接的终端"); return; }
        for (AgentActivityBus.Event event : history.getOrDefault(historyKey(tab), List.of())) printEvent(event);
        console.requestInputFocus();
    }

    private void updateTerminalStatus(Tab tab) {
        if (tab == null) { console.setStatus("未选择连接 · 输入 / 查看命令"); return; }
        String taskId = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        String task = connectionTasks.getOrDefault(connectionKey(tab), List.of()).stream()
                .filter(item -> item.id().equals(taskId)).map(AgentConversation::title).findFirst().orElse("-");
        AIConfig model = AIConfigManager.getInstance().getCurrentConfig();
        console.setStatus(task + " · " + (model == null ? "未配置模型" : model.getModel()));
    }

    private String formatEvent(AgentActivityBus.Event event) {
        String text = escape(event.text());
        String indented = text.replace("\r\n", "\r\n  ");
        return switch (event.kind()) {
            case USER -> color("f0f6fc") + "❯ " + text + RESET;
            case THINK -> color("8b949e") + "✻ " + indented + RESET;
            case RESPONSE -> color("f0f6fc") + indented + RESET;
            case TOOL -> color("d2a8ff") + "● " + indented + RESET;
            case PROPOSAL -> color("ffd866") + "● Bash  " + indented + RESET;
            case EXECUTION -> color("58a6ff") + "● Running  " + indented + RESET;
            case ASK -> color("ffd866") + "? " + indented + RESET;
            case SUCCESS -> color("56d364") + "✓ " + indented + RESET;
            case ERROR -> color("ff7b72") + "✗ " + indented + RESET;
            case CANCELLED -> color("d29922") + "■ " + indented + RESET;
            case STATUS -> color("8b949e") + indented + RESET;
        };
    }

    private void stopAgent() {
        Tab tab = activeTab();
        if (tab != null) {
            pendingQuestions.remove(tab.getId());
            if (commandService != null) commandService.cancelAIRequest(tab);
        }
        agentRunning = false;
    }

    private void clearCurrent() {
        Tab tab = activeTab();
        if (tab != null) history.remove(historyKey(tab));
        console.welcome();
    }

    private void status(String text) { console.print(color("79c0ff") + escape(text) + RESET); }
    private void error(String text) { console.print(color("ff7b72") + escape(text) + RESET); }
    private String escape(String text) { return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n"); }
    private String color(String hex) { return "\u001b[38;2;" + Integer.parseInt(hex.substring(0, 2), 16) + ';'
            + Integer.parseInt(hex.substring(2, 4), 16) + ';' + Integer.parseInt(hex.substring(4, 6), 16) + 'm'; }
    private String eventColor(AgentActivityBus.Kind kind) { return switch (kind) {
        case THINK, STATUS -> "8b949e"; case TOOL -> "d2a8ff"; case RESPONSE -> "79c0ff";
        case PROPOSAL, SUCCESS -> "56d364"; case ASK -> "ffd866"; case EXECUTION -> "ffa657";
        case ERROR -> "ff7b72"; case CANCELLED -> "d29922"; default -> "f0f6fc";
    }; }
    private String eventLabel(AgentActivityBus.Kind kind) { return switch (kind) {
        case THINK -> "思考"; case RESPONSE -> "响应"; case TOOL -> "工具"; case PROPOSAL -> "提议";
        case ASK -> "提问"; case EXECUTION -> "执行"; case SUCCESS -> "完成"; case ERROR -> "错误";
        case CANCELLED -> "停止"; case USER -> "用户"; default -> "状态";
    }; }
    private Tab activeTab() { return activeTabSupplier.get(); }
    private String historyKey(Tab tab) { return connectionKey(tab) + "::" + tab.getProperties().getOrDefault(AGENT_TASK_ID, "default"); }
    private String connectionKey(Tab tab) {
        if (tab == null || tab.getId() == null) return "none";
        try {
            var ssh = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getSshService();
            var params = ssh == null ? null : ssh.getTabConnectionParams(tab.getId());
            if (params != null && params.getHost() != null) return (params.getUsername() == null ? "" : params.getUsername())
                    + '@' + params.getHost() + ':' + params.getPort();
        } catch (Exception ignored) { }
        return tab.getId();
    }

    private record AgentConversation(String id, String title) { }
}
