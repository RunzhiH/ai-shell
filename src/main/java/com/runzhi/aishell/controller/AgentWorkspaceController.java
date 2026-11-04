/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.agent.AgentHistoryStore;
import com.runzhi.aishell.agent.AgentHistoryWriter;
import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.service.TerminalCommandService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ui.AgentConsoleView;
import com.runzhi.aishell.ui.AgentHistoryDialog;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** Claude-style Agent workspace bound to the active SSH tab. */
public class AgentWorkspaceController implements com.runzhi.aishell.common.util.Cleanable {
    private static final String AUTO_LOW_RISK = "agentAutoApproveLowRisk";
    private static final String AGENT_TASK_ID = "agentTaskId";
    private static final String SAVE_HISTORY_PREF = "general.agentHistory";
    private static final String AUTO_APPROVE_PREF_PREFIX = "agent.autoLowRisk.";
    private static final int MAX_PERSISTED_TASKS_PER_CONNECTION = 50;
    private static final int EXPORT_PREVIEW_CHARS = 4000;
    /** Construct display text in the current language; command names stay protocol identifiers. */
    private static List<AgentConsoleView.Command> commands() {
        return List.of(
                new AgentConsoleView.Command("/help", I18n.tr("agent.cli.help")),
                new AgentConsoleView.Command("/new", I18n.tr("agent.cli.new")),
                new AgentConsoleView.Command("/tasks", I18n.tr("agent.cli.tasks")),
                new AgentConsoleView.Command("/resume", I18n.tr("agent.cli.resume")),
                new AgentConsoleView.Command("/continue", I18n.tr("agent.cli.continue")),
                new AgentConsoleView.Command("/rename", I18n.tr("agent.cli.rename")),
                new AgentConsoleView.Command("/delete", I18n.tr("agent.cli.delete")),
                new AgentConsoleView.Command("/clear-history", I18n.tr("agent.cli.clearHistory")),
                new AgentConsoleView.Command("/model", I18n.tr("agent.cli.model")),
                new AgentConsoleView.Command("/context", I18n.tr("agent.cli.context")),
                new AgentConsoleView.Command("/permissions", I18n.tr("agent.cli.permissions")),
                new AgentConsoleView.Command("/auto", I18n.tr("agent.cli.auto")),
                new AgentConsoleView.Command("/stop", I18n.tr("agent.cli.stop")),
                new AgentConsoleView.Command("/clear", I18n.tr("agent.cli.clear")));
    }

    /** Help and completion share the same catalog, including the command itself. */
    private String commandHelp() {
        Map<String,String> arguments = Map.of("/new", " [name]", "/resume", " N", "/model", " [N|profile]",
                "/auto", " on|off", "/rename", " <title>", "/delete", "", "/clear-history", "");
        StringBuilder result = new StringBuilder(I18n.tr("agent.cli.commands"));
        for (AgentConsoleView.Command command : commands()) result.append("\r\n  ")
                .append(String.format("%-20s %s", command.name() + arguments.getOrDefault(command.name(), ""), command.description()));
        return result.toString();
    }

    /** The activity subscription belongs to this controller, not to panel visibility. */
    private AutoCloseable activitySubscription;
    private volatile boolean disposed;
    @FXML private StackPane terminalHost;
    private final Map<String, List<AgentActivityBus.Event>> history = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> sessionHistoryKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<AgentConversation>> connectionTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AgentActivityBus.Event> pendingQuestions = new java.util.concurrent.ConcurrentHashMap<>();
    private final AgentHistoryStore historyStore = new AgentHistoryStore();
    private final AgentHistoryWriter historyWriter = new AgentHistoryWriter(historyStore);
    private final Preferences prefs = Preferences.userNodeForPackage(AgentWorkspaceController.class);
    private AgentHistoryDialog historyDialog;
    private boolean historyLoaded;
    private Supplier<Tab> activeTabSupplier = () -> null;
    private AgentWorkspaceBridge bridge;
    private TerminalCommandService commandService;
    private AgentConsoleView console;

    @FXML
    private void initialize() {
        console = new AgentConsoleView(commands(),
                line -> Platform.runLater(() -> handleLine(line)),
                () -> Platform.runLater(this::stopAgent),
                () -> Platform.runLater(this::clearCurrent));
        terminalHost.getChildren().add(console);
        console.welcome();
        activitySubscription = AgentActivityBus.subscribe(this::receive);
        Platform.runLater(console::requestInputFocus);
    }

    public void configure(Supplier<Tab> activeTabSupplier, TerminalCommandService commandService,
                          AgentWorkspaceBridge bridge) {
        this.activeTabSupplier = activeTabSupplier == null ? () -> null : activeTabSupplier;
        this.commandService = commandService;
        this.bridge = bridge;
        loadPersistedHistory();
        if (console != null) {
            console.setHistoryAction(this::showHistory);
            console.setAgentMenuActions(new AgentConsoleView.AgentMenuActions() {
                @Override public void newTask() { AgentWorkspaceController.this.requestNewTask(); }
                @Override public void showHistory() { AgentWorkspaceController.this.showHistory(); }
                @Override public void showPermissions() { AgentWorkspaceController.this.showPermissions(); }
                @Override public void toggleAutoApprove() { AgentWorkspaceController.this.toggleAutoApprove(); }
                @Override public void clearTask() { AgentWorkspaceController.this.clearCurrent(); }
                @Override public void deleteTask() { AgentWorkspaceController.this.deleteCurrentTask(activeTab()); }
            });
            console.setArgumentSuggestions(this::argumentSuggestions);
        }
        showTab(activeTab());
    }

    /** Agent 工作区始终显示传入标签的会话；切换终端时由面板控制器传入新选中的标签。 */
    public void showTab(Tab tab) {
        if(tab!=null && "terminal-hint-tab".equals(tab.getId())) tab=null;
        if (tab != null && tab.getId() != null) {
            configureTasks(tab);
            restoreAutoApprove(tab);
            String key = historyKey(tab);
            adoptEarlyHistory(tab.getId(), key);
            sessionHistoryKeys.put(tab.getId(), key);
        }
        // 直接构造（无 FXML 注入）时没有 console，只维护会话身份即可
        if (console == null) return;
        render(tab);
    }

    /**
     * 会话首次显示前到达的事件按原始 sessionId 暂存；工作区从未显示该标签时（如终端 ai: 命令）
     * 也要在首次渲染时把这些记录并入正式的任务历史，不能因为键切换而丢失。
     */
    private void adoptEarlyHistory(String sessionId, String targetKey) {
        List<AgentActivityBus.Event> early = history.remove(sessionId);
        if (early == null || early.isEmpty() || targetKey.equals(sessionId)) return;
        List<AgentActivityBus.Event> target = history.computeIfAbsent(targetKey, ignored -> new ArrayList<>());
        target.addAll(early);
        if (target.size() > 300) target.subList(0, target.size() - 300).clear();
    }

    private void handleLine(String line) {
        if (disposed) return;
        Tab tab = activeTab();
        if (tab == null || tab.getId() == null) { status(I18n.tr("agent.workspace.noConnectedTab")); return; }
        // 所有 / 命令都是 Agent 本地行为，必须在待回答问题之前处理；
        // 否则工具提问期间输入 /help、/tasks 等会被当成回答直接发送给大模型。
        if (line.stripLeading().startsWith("/")) {
            handleCommand(line);
            return;
        }
        AgentActivityBus.Event question = pendingQuestions.get(tab.getId());
        if (question != null && question.answerHandler() != null) {
            String answer = resolveAnswer(question, line);
            // Claim only this question; a replacement must never receive an answer intended for the old one.
            if (!pendingQuestions.remove(tab.getId(), question)) {
                error(I18n.tr("agent.workspace.questionChanged"));
                return;
            }
            try {
                question.answerHandler().accept(answer);
            } catch (RuntimeException failure) {
                // Restore for retry, unless the callback has already installed the next question.
                if (!disposed) {
                    pendingQuestions.putIfAbsent(tab.getId(), question);
                    error(I18n.tr("agent.workspace.answerFailed"));
                }
                return;
            }
            // 输入框已即时回显；只把用户实际输入的原文写入历史，解析后的 answer 仅交给回调。
            if (!disposed) recordUserInput(tab, line);
            return;
        }
        String sessionId = tab.getId();
        if (AgentSessionTracker.getInstance().isRunning(sessionId)) {
            status(I18n.tr("agent.workspace.runningHint"));
            return;
        }
        if (commandService == null) { status(I18n.tr("agent.workspace.serviceNotReady")); return; }
        commandService.processAITerminalCommand(tab, line);
        AgentSessionTracker.getInstance().markRunning(sessionId);
        refreshAiProfileSelector(tab);
    }

    /** 答问路径不经过 AgentActivityBus，因此显式补一条真实用户输入到会话历史供回放。 */
    private void recordUserInput(Tab tab, String text) {
        if (tab == null || tab.getId() == null || text == null || text.isBlank()) return;
        String key = historyKey(tab);
        List<AgentActivityBus.Event> events = history.computeIfAbsent(key, ignored -> new ArrayList<>());
        events.add(new AgentActivityBus.Event(tab.getId(), AgentActivityBus.Kind.USER, text,
                java.time.Instant.now()));
        if (events.size() > 300) events.subList(0, events.size() - 300).clear();
    }

    private String resolveAnswer(AgentActivityBus.Event question, String line) {
        String value = line == null ? "" : line.trim();
        if (value.matches("[0-9]+")) {
            try {
                int index = Integer.parseInt(value) - 1;
                if (index >= 0 && index < question.options().size()) return question.options().get(index);
            } catch (NumberFormatException notAnOptionNumber) {
                // Large numeric values remain valid free-text answers.
            }
        }
        return value;
    }

    private void handleCommand(String input) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length == 2 ? parts[1].trim() : "";
        Tab tab = activeTab();
        String sessionId = tab == null ? null : tab.getId();
        switch (command) {
            case "/help" -> status(commandHelp());
            case "/new" -> { if (!rejectRunning(I18n.tr("agent.workspace.operationNew"), sessionId)) newTask(argument); }
            case "/tasks" -> status(taskList(tab));
            case "/resume" -> { if (!rejectRunning(I18n.tr("agent.workspace.operationTask"), sessionId)) switchTaskByArgument(tab, argument); }
            case "/continue" -> { if (!rejectRunning(I18n.tr("agent.workspace.operationTask"), sessionId)) continueLatest(tab); }
            case "/rename" -> { if (!rejectRunning(I18n.tr("agent.workspace.operationRename"), sessionId)) renameCurrentTask(tab, argument); }
            case "/delete" -> { if (!rejectRunning(I18n.tr("agent.workspace.operationDelete"), sessionId)) deleteCurrentTask(tab); }
            case "/clear-history" -> { if (!rejectRunning(I18n.tr("agent.workspace.operationDelete"), sessionId)) clearConnectionHistory(tab); }
            case "/model" -> { if (argument.isBlank()) status(modelList()); else if (!rejectRunning(I18n.tr("agent.workspace.operationModel"), sessionId)) switchModel(argument); }
            case "/context" -> status(contextSummary(tab));
            case "/permissions" -> status(permissionSummary(tab));
            case "/auto" -> changeAuto(tab, argument);
            case "/stop" -> stopAgent();
            case "/clear" -> clearCurrent();
            default -> error(I18n.tr("agent.workspace.unknownCommand", command));
        }
    }

    private boolean rejectRunning(String operation, String sessionId) {
        if (sessionId == null || !AgentSessionTracker.getInstance().isRunning(sessionId)) return false;
        status(I18n.tr("agent.workspace.operationBlocked", operation));
        return true;
    }

    /**
     * 每个终端标签拥有自己的默认会话；同一连接的旧会话仍保留在任务列表中，可用 /resume 恢复。
     * 不能把连接下的第一个会话直接分给新标签，否则同一主机的两个终端会共享同一段 Agent 记录。
     */
    private void configureTasks(Tab tab) {
        List<AgentConversation> tasks = connectionTasks.computeIfAbsent(connectionKey(tab), ignored -> new ArrayList<>());
        String selectedId = (String) tab.getProperties().get(AGENT_TASK_ID);
        AgentConversation selected = tasks.stream().filter(task -> task.id().equals(selectedId)).findFirst().orElse(null);
        if (selected == null) {
            selected = new AgentConversation(UUID.randomUUID().toString(), null, tasks.size() + 1);
            tasks.add(selected);
            tab.getProperties().put(AGENT_TASK_ID, selected.id());
            persistHistory();
            return;
        }
        tab.getProperties().put(AGENT_TASK_ID, selected.id());
    }

    /** 启动时加载历史任务与消息；只回放文本，不恢复审批/回答回调。 */
    private void loadPersistedHistory() {
        if (historyLoaded) return;
        historyLoaded = true;
        for (AgentHistoryStore.TaskRecord record : historyStore.load()) {
            String connection = record.connectionKey();
            String taskId = record.id();
            if (connection == null || taskId == null) continue;
            List<AgentConversation> tasks = connectionTasks.computeIfAbsent(connection, ignored -> new ArrayList<>());
            if (tasks.stream().noneMatch(task -> task.id().equals(taskId))) {
                tasks.add(new AgentConversation(taskId, record.title(), Math.max(1, record.ordinal())));
            }
            String key = connection + "::" + taskId;
            if (!history.containsKey(key)) {
                List<AgentActivityBus.Event> events = record.events().stream()
                        .map(AgentHistoryStore::toEvent)
                        .filter(event -> event != null)
                        .toList();
                if (!events.isEmpty()) history.put(key, new ArrayList<>(events));
            }
        }
        for (List<AgentConversation> tasks : connectionTasks.values()) {
            tasks.sort(Comparator.comparingInt(AgentConversation::ordinal));
        }
    }

    /** 保存当前所有连接的任务元数据与可回放消息（FX 线程快照 + 后台异步写盘）。 */
    private void persistHistory() {
        if (!historyLoaded || !historySavingEnabled()) return;
        List<AgentHistoryStore.TaskRecord> records = new ArrayList<>();
        for (Map.Entry<String, List<AgentConversation>> entry : connectionTasks.entrySet()) {
            List<AgentConversation> tasks = entry.getValue();
            for (int index = 0; index < tasks.size(); index++) {
                AgentConversation task = tasks.get(index);
                String key = entry.getKey() + "::" + task.id();
                List<AgentActivityBus.Event> events = history.getOrDefault(key, List.of());
                if (events.size() > 300) events = events.subList(events.size() - 300, events.size());
                long updated = events.isEmpty() ? System.currentTimeMillis()
                        : events.stream().map(event -> event.timestamp() == null ? 0L : event.timestamp().toEpochMilli())
                                .max(Long::compare).orElse(System.currentTimeMillis());
                records.add(new AgentHistoryStore.TaskRecord(entry.getKey(), task.id(), task.title(),
                        task.ordinal(), updated,
                        events.stream().map(AgentHistoryStore::toRecord)
                                .filter(event -> event != null).toList()));
            }
        }
        historyWriter.submit(records);
    }

    /** 设置页“保存 Agent 历史”开关；关闭后不产生/不更新历史文件。 */
    private boolean historySavingEnabled() {
        return prefs.getBoolean(SAVE_HISTORY_PREF, true);
    }

    /** 打开当前连接的会话历史面板：可搜索、恢复、重命名、删除、清空、导出。 */
    private void showHistory() {
        Tab tab = activeTab();
        if (tab == null || tab.getId() == null) {
            status(I18n.tr("agent.workspace.noConnectedTab"));
            return;
        }
        if (console == null) return;
        String connection = connectionKey(tab);
        List<AgentConversation> tasks = new ArrayList<>(connectionTasks.getOrDefault(connection, List.of()));
        if (tasks.isEmpty()) {
            status(I18n.tr("agent.history.empty"));
            return;
        }
        tasks.sort(Comparator.comparingInt(AgentConversation::ordinal).reversed());
        String currentId = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        List<AgentHistoryDialog.HistoryItem> items = new ArrayList<>();
        for (AgentConversation task : tasks) {
            items.add(AgentHistoryDialog.from(taskRecordFor(connection, task), connection, task.id().equals(currentId)));
        }
        historyDialog = new AgentHistoryDialog(console.getScene() == null ? null : console.getScene().getWindow(),
                items, new HistoryActions(tab, connection));
        historyDialog.show();
    }

    /** 历史面板动作实现：所有修改都落回任务模型、工作区显示与持久化。 */
    private final class HistoryActions implements AgentHistoryDialog.HistoryActions {
        private final Tab tab;
        private final String connection;

        private HistoryActions(Tab tab, String connection) {
            this.tab = tab;
            this.connection = connection;
        }

        @Override public void resume(String taskId) {
            AgentConversation target = taskById(connection, taskId);
            if (target == null) return;
            if (rejectRunning(I18n.tr("agent.workspace.operationTask"), tab.getId())) return;
            switchTask(tab, target);
            status(I18n.tr("agent.workspace.resumed", target.displayTitle()));
        }

        @Override public void rename(String taskId, String title) {
            List<AgentConversation> tasks = connectionTasks.get(connection);
            if (tasks == null) return;
            for (int index = 0; index < tasks.size(); index++) {
                AgentConversation task = tasks.get(index);
                if (task.id().equals(taskId)) {
                    tasks.set(index, new AgentConversation(task.id(), title, task.ordinal()));
                    persistHistory();
                    if (taskId.equals(String.valueOf(tab.getProperties().get(AGENT_TASK_ID)))) {
                        updateTerminalStatus(tab);
                    }
                    status(I18n.tr("agent.workspace.renamed", title));
                    return;
                }
            }
        }

        @Override public void delete(String taskId) {
            List<AgentConversation> tasks = connectionTasks.get(connection);
            if (tasks == null) return;
            AgentConversation removed = tasks.stream().filter(task -> task.id().equals(taskId))
                    .findFirst().orElse(null);
            if (removed == null) return;
            tasks.remove(removed);
            history.remove(connection + "::" + taskId);
            if (taskId.equals(String.valueOf(tab.getProperties().get(AGENT_TASK_ID)))) {
                // 删除的是当前任务：有旧任务则恢复到最近任务，否则新建空任务
                if (tasks.isEmpty()) { newTaskForTab(tab, ""); }
                else switchTask(tab, tasks.get(tasks.size() - 1));
            }
            persistHistory();
            status(I18n.tr("agent.workspace.deleted", removed.displayTitle()));
            if (historyDialog != null) historyDialog.close();
        }

        @Override public void clearAll() {
            List<AgentConversation> tasks = connectionTasks.remove(connection);
            if (tasks != null) {
                for (AgentConversation task : tasks) history.remove(connection + "::" + task.id());
            }
            persistHistory();
            newTaskForTab(tab, "");
            status(I18n.tr("agent.workspace.historyCleared"));
            if (historyDialog != null) historyDialog.close();
        }

        @Override public void export(Path file) {
            exportHistory(connection, file);
        }
    }

    /** 把连接下的任务与消息导出为 Markdown。 */
    private void exportHistory(String connection, Path file) {
        List<AgentConversation> tasks = new ArrayList<>(connectionTasks.getOrDefault(connection, List.of()));
        tasks.sort(Comparator.comparingInt(AgentConversation::ordinal));
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(I18n.tr("agent.history.exportTitle", connection)).append("\n\n");
        for (AgentConversation task : tasks) {
            markdown.append("## ").append(task.displayTitle()).append("\n\n");
            List<AgentActivityBus.Event> events = history.getOrDefault(connection + "::" + task.id(), List.of());
            if (events.isEmpty()) {
                markdown.append(I18n.tr("agent.history.exportNoMessages")).append("\n\n");
                continue;
            }
            for (AgentActivityBus.Event event : events) {
                markdown.append("**").append(I18n.tr("agent.role." + event.kind().name().toLowerCase(Locale.ROOT)))
                        .append("** · ").append(event.timestamp() == null ? "-" : event.timestamp().toString())
                        .append("\n\n").append(event.text() == null ? "" : event.text())
                        .append("\n\n---\n\n");
            }
        }
        try {
            Files.writeString(file, markdown.toString(), StandardCharsets.UTF_8);
            status(I18n.tr("agent.history.exportDone", file.getFileName().toString()));
        } catch (IOException failure) {
            error(I18n.tr("agent.history.exportFailed", failure.getMessage()));
        }
    }

    private void newTask(String requestedTitle) {
        Tab tab = activeTabSupplier.get();
        newTaskForTab(tab,requestedTitle);
    }

    private void newTaskForTab(Tab tab, String requestedTitle) {
        if (tab == null) return;
        if (AgentSessionTracker.getInstance().isRunning(tab.getId()) || AgentSessionTracker.getInstance().pendingApprovals(tab.getId())>0) {
            status(I18n.tr("agent.workspace.runningHint")); return;
        }
        List<AgentConversation> tasks = connectionTasks.computeIfAbsent(connectionKey(tab), ignored -> new ArrayList<>());
        String title = requestedTitle.isBlank() ? null : requestedTitle;
        AgentConversation task = new AgentConversation(UUID.randomUUID().toString(), title, tasks.size() + 1);
        tasks.add(task);
        switchTask(tab, task);
        status(I18n.tr("agent.workspace.created", task.displayTitle()));
    }

    private void switchTask(Tab tab, AgentConversation task) {
        tab.getProperties().put(AGENT_TASK_ID, task.id());
        sessionHistoryKeys.put(tab.getId(), historyKey(tab));
        render(tab);
        persistHistory();
    }

    private void switchTaskByArgument(Tab tab, String argument) {
        List<AgentConversation> tasks = tab == null ? List.of() : connectionTasks.getOrDefault(connectionKey(tab), List.of());
        try {
            int index = Integer.parseInt(argument) - 1;
            if (index < 0 || index >= tasks.size()) throw new NumberFormatException();
            switchTask(tab, tasks.get(index));
            status(I18n.tr("agent.workspace.resumed", tasks.get(index).displayTitle()));
        } catch (NumberFormatException e) { error(I18n.tr("agent.workspace.invalidTask")); }
    }

    private void continueLatest(Tab tab) {
        List<AgentConversation> tasks = tab == null ? List.of() : connectionTasks.getOrDefault(connectionKey(tab), List.of());
        if (tasks.isEmpty()) { error(I18n.tr("agent.workspace.noTasks")); return; }
        switchTask(tab, tasks.get(tasks.size() - 1));
        status(I18n.tr("agent.workspace.continued", tasks.get(tasks.size() - 1).displayTitle()));
    }

    /** /rename <标题>：修改当前任务标题并写回历史。 */
    private void renameCurrentTask(Tab tab, String argument) {
        if (tab == null || argument.isBlank()) { error(I18n.tr("agent.workspace.renameUsage")); return; }
        AgentConversation current = currentTask(tab);
        if (current == null) { error(I18n.tr("agent.workspace.noTasks")); return; }
        List<AgentConversation> tasks = connectionTasks.get(connectionKey(tab));
        if (tasks == null) return;
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).id().equals(current.id())) {
                tasks.set(index, new AgentConversation(current.id(), argument.trim(), current.ordinal()));
                persistHistory();
                updateTerminalStatus(tab);
                status(I18n.tr("agent.workspace.renamed", argument.trim()));
                return;
            }
        }
    }

    /** /delete：删除当前任务及其消息；有旧任务则恢复到最近任务，否则新建空任务。 */
    private void deleteCurrentTask(Tab tab) {
        if (tab == null) return;
        if (rejectRunning(I18n.tr("agent.workspace.operationDelete"), tab.getId())) return;
        AgentConversation current = currentTask(tab);
        if (current == null) { error(I18n.tr("agent.workspace.noTasks")); return; }
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                I18n.tr("agent.workspace.deleteConfirm", current.displayTitle()),
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        confirm.setTitle(I18n.tr("agent.workspace.deleteTitle"));
        confirm.setHeaderText(I18n.tr("agent.workspace.deleteHeader"));
        if (confirm.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).isEmpty()) return;
        List<AgentConversation> tasks = connectionTasks.get(connectionKey(tab));
        if (tasks != null) tasks.remove(current);
        history.remove(historyKey(tab));
        if (tasks == null || tasks.isEmpty()) newTaskForTab(tab, "");
        else switchTask(tab, tasks.get(tasks.size() - 1));
        persistHistory();
        status(I18n.tr("agent.workspace.deleted", current.displayTitle()));
    }

    /** /clear-history：清空当前连接的全部历史，需确认；不影响其他连接。 */
    private void clearConnectionHistory(Tab tab) {
        if (tab == null) return;
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                I18n.tr("agent.workspace.clearHistoryConfirm"),
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        confirm.setTitle(I18n.tr("agent.workspace.deleteTitle"));
        confirm.setHeaderText(I18n.tr("agent.workspace.clearHistoryHeader"));
        if (confirm.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).isEmpty()) return;
        String connection = connectionKey(tab);
        List<AgentConversation> tasks = connectionTasks.remove(connection);
        if (tasks != null) for (AgentConversation task : tasks) history.remove(connection + "::" + task.id());
        persistHistory();
        newTaskForTab(tab, "");
        status(I18n.tr("agent.workspace.historyCleared"));
    }

    private AgentConversation currentTask(Tab tab) {
        if (tab == null || tab.getId() == null) return null;
        String taskId = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        return taskById(connectionKey(tab), taskId);
    }

    private AgentConversation taskById(String connection, String taskId) {
        if (taskId == null) return null;
        return connectionTasks.getOrDefault(connection, List.of()).stream()
                .filter(task -> task.id().equals(taskId)).findFirst().orElse(null);
    }

    /** 由当前事件构建可回放记录；updatedAt 取最后一条事件时间，使历史列表排序有意义。 */
    private AgentHistoryStore.TaskRecord taskRecordFor(String connection, AgentConversation task) {
        List<AgentActivityBus.Event> events = new ArrayList<>(history.getOrDefault(connection + "::" + task.id(), List.of()));
        List<AgentHistoryStore.EventRecord> records = events.stream()
                .map(AgentHistoryStore::toRecord).filter(Objects::nonNull).toList();
        long updated = records.isEmpty() ? System.currentTimeMillis()
                : records.stream().mapToLong(AgentHistoryStore.EventRecord::timestamp).max().orElse(System.currentTimeMillis());
        return new AgentHistoryStore.TaskRecord(connection, task.id(), task.title(), task.ordinal(), updated, records);
    }

    private String taskList(Tab tab) {
        if (tab == null) return I18n.tr("agent.workspace.noTab");
        List<AgentConversation> tasks = connectionTasks.getOrDefault(connectionKey(tab), List.of());
        String active = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        StringBuilder result = new StringBuilder(I18n.tr("agent.workspace.taskList"));
        for (int i = 0; i < tasks.size(); i++) result.append("\r\n ")
                .append(tasks.get(i).id().equals(active) ? "*" : " ").append(' ')
                .append(i + 1).append("  ").append(tasks.get(i).displayTitle());
        return result.toString();
    }

    private String modelList() {
        List<AIConfig> configs = AIConfigManager.getInstance().getAllConfigs();
        AIConfig current = AIConfigManager.getInstance().getCurrentConfig();
        StringBuilder result = new StringBuilder(I18n.tr("agent.workspace.modelList"));
        for (int i = 0; i < configs.size(); i++) {
            AIConfig config = configs.get(i);
            result.append("\r\n ").append(current != null && config.getName().equals(current.getName()) ? "*" : " ")
                    .append(' ').append(i + 1).append("  ").append(config.getName()).append(" · ").append(config.getModel());
        }
        return result.toString();
    }

    private void switchModel(String argument) {
        List<AIConfig> configs = AIConfigManager.getInstance().getAllConfigs();
        AIConfig selected;
        try { selected = findModel(argument, configs); }
        catch (IllegalArgumentException ambiguous) {
            error(ambiguous.getMessage());
            refreshAiProfileSelector(activeTab());
            return;
        }
        if (selected == null) {
            error(I18n.tr("agent.workspace.modelNotFound"));
            refreshAiProfileSelector(activeTab());
            return;
        }
        AIConfigManager.getInstance().setDefaultConfig(selected.getName());
        var aiService = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getAiService();
        if (aiService != null) aiService.reinitializeAdapter();
        updateTerminalStatus(activeTab());
        audit("model_switched", "model: " + selected.getName() + " · " + selected.getModel(), "switched");
        status(I18n.tr("agent.workspace.modelChanged", selected.getName(), selected.getModel()));
    }

    /** 工作区底部的 AI 配置切换入口；运行中的任务保持同一配置到结束。 */
    private void switchAiProfile(String name) {
        if (name == null || name.isBlank()) return;
        Tab tab = activeTab();
        if (tab != null && AgentSessionTracker.getInstance().isRunning(tab.getId())) {
            status(I18n.tr("agent.workspace.operationBlocked", I18n.tr("agent.workspace.operationModel")));
            refreshAiProfileSelector(tab);
            return;
        }
        switchModel(name);
        refreshAiProfileSelector(activeTab());
    }

    private void refreshAiProfileSelector(Tab tab) {
        if (console == null) return;
        List<AIConfig> configs = AIConfigManager.getInstance().getAllConfigs();
        Map<String, Integer> modelCounts = new java.util.HashMap<>();
        for (AIConfig config : configs) {
            String base = modelLabel(config);
            modelCounts.merge(base, 1, Integer::sum);
        }
        List<AgentConsoleView.AiProfile> profiles = new ArrayList<>();
        for (AIConfig config : configs) {
            String base = modelLabel(config);
            String label = modelCounts.getOrDefault(base, 0) > 1 ? base + " · " + config.getName() : base;
            profiles.add(new AgentConsoleView.AiProfile(config.getName(), label));
        }
        AIConfig current = AIConfigManager.getInstance().getCurrentConfig();
        console.setAiProfiles(profiles, current == null ? null : current.getName(), this::switchAiProfile);
        boolean running = tab != null && tab.getId() != null
                && AgentSessionTracker.getInstance().isRunning(tab.getId());
        console.setAiSwitchingEnabled(!running && !profiles.isEmpty());
    }

    /** 下拉框优先展示模型；没有模型名时退回配置名。 */
    private static String modelLabel(AIConfig config) {
        String model = config.getModel();
        if (model == null || model.isBlank()) return config.getName();
        return model.trim();
    }

    /** Prefer profile identity; a duplicated model alias requires an explicit profile or index. */
    static AIConfig findModel(String argument, List<AIConfig> configs) {
        if (argument == null || argument.isBlank()) return null;
        try {
            int index = Integer.parseInt(argument) - 1;
            return index >= 0 && index < configs.size() ? configs.get(index) : null;
        } catch (NumberFormatException notAnIndex) {
            List<AIConfig> matches = configs.stream()
                    .filter(config -> argument.equalsIgnoreCase(config.getName())).toList();
            if (matches.isEmpty()) matches = configs.stream()
                    .filter(config -> argument.equalsIgnoreCase(config.getModel())).toList();
            if (matches.size() > 1) throw new IllegalArgumentException(I18n.tr("agent.workspace.ambiguousModel"));
            return matches.isEmpty() ? null : matches.get(0);
        }
    }

    private void changeAuto(Tab tab, String argument) {
        if (tab == null || !(argument.equalsIgnoreCase("on") || argument.equalsIgnoreCase("off"))) { error(I18n.tr("agent.workspace.autoUsage")); return; }
        setAutoApprove(tab, argument.equalsIgnoreCase("on"), "/auto " + argument);
    }

    /** 设置自动批准：Tab property 作运行期缓存，Preferences 持久化；切换标签/重启后恢复。 */
    private void setAutoApprove(Tab tab, boolean enabled, String source) {
        if (tab == null) return;
        tab.getProperties().put(AUTO_LOW_RISK, enabled);
        prefs.putBoolean(autoApprovePrefKey(tab), enabled);
        audit("auto_approve_changed", source, enabled ? "on" : "off");
        refreshConsoleControls(tab);
        status(I18n.tr("agent.workspace.autoChanged", I18n.tr(enabled ? "agent.workspace.on" : "agent.workspace.off")));
    }

    /** 首次显示标签时从 Preferences 恢复自动批准策略；运行期已显式设置的不覆盖。 */
    private void restoreAutoApprove(Tab tab) {
        if (tab == null || tab.getProperties().containsKey(AUTO_LOW_RISK)) return;
        tab.getProperties().put(AUTO_LOW_RISK, prefs.getBoolean(autoApprovePrefKey(tab), false));
    }

    private String autoApprovePrefKey(Tab tab) {
        return AUTO_APPROVE_PREF_PREFIX + connectionKey(tab).replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    /** 同步“更多”菜单勾选/置灰与 AI 切换可用性；状态来源全部是当前标签。 */
    private void refreshConsoleControls(Tab tab) {
        if (console == null) return;
        boolean running = tab != null && tab.getId() != null
                && AgentSessionTracker.getInstance().isRunning(tab.getId());
        boolean auto = tab != null && Boolean.TRUE.equals(tab.getProperties().get(AUTO_LOW_RISK));
        console.setAutoApproveLowRisk(auto, running);
        console.setTaskMenuEnabled(!running,
                I18n.tr("agent.workspace.operationBlocked", I18n.tr("agent.workspace.operationTask")));
        refreshAiProfileSelector(tab);
    }

    /** 控制动作（开关、切换）也入审计，保证责任链完整。 */
    private void audit(String event, String subject, String result) {
        Tab tab = activeTab();
        com.runzhi.aishell.agent.AgentAuditLogger.getInstance().record(
                event, tab == null || tab.getId() == null ? "unknown" : tab.getId(),
                com.runzhi.aishell.agent.CommandRisk.LOW, subject, result);
    }

    private String contextSummary(Tab tab) {
        if (tab == null) return I18n.tr("agent.workspace.noTab");
        String taskId = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        String task = connectionTasks.getOrDefault(connectionKey(tab), List.of()).stream()
                .filter(item -> item.id().equals(taskId)).map(AgentConversation::displayTitle).findFirst().orElse("-");
        AIConfig model = AIConfigManager.getInstance().getCurrentConfig();
        List<AgentActivityBus.Event> events = history.getOrDefault(historyKey(tab), List.of());
        int estimatedTokens = events.stream()
                .mapToInt(event -> event.text() == null ? 0 : event.text().length()).sum() / 4;
        return I18n.tr("agent.workspace.context", connectionKey(tab), task,
                model == null ? I18n.tr("agent.workspace.unconfigured") : model.getName() + " · " + model.getModel(),
                String.valueOf(events.size()), String.valueOf(estimatedTokens));
    }

    private String permissionSummary(Tab tab) {
        boolean auto = tab != null && Boolean.TRUE.equals(tab.getProperties().get(AUTO_LOW_RISK));
        return I18n.tr("agent.workspace.permissions", I18n.tr(auto ? "agent.workspace.autoApprove" : "agent.workspace.confirmBefore"));
    }

    /** “更多”菜单 → 新建任务：与 /new 一致，可带标题。 */
    private void requestNewTask() {
        Tab tab = activeTab();
        if (tab == null) { status(I18n.tr("agent.workspace.noConnectedTab")); return; }
        if (rejectRunning(I18n.tr("agent.workspace.operationNew"), tab.getId())) return;
        javafx.scene.control.TextInputDialog titleDialog = new javafx.scene.control.TextInputDialog();
        titleDialog.setTitle(I18n.tr("agent.menu.newTask"));
        titleDialog.setHeaderText(I18n.tr("agent.menu.newTaskHeader"));
        titleDialog.setContentText(I18n.tr("agent.workspace.newTaskPrompt"));
        String title = titleDialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty()).orElse("");
        newTaskForTab(tab, title);
    }

    /** “更多”菜单 → 执行权限：展示当前策略，入口与 /permissions 一致。 */
    private void showPermissions() {
        status(permissionSummary(activeTab()));
    }

    /** “更多”菜单 → 自动批准低风险开关；高风险始终需二次确认。 */
    private void toggleAutoApprove() {
        Tab tab = activeTab();
        if (tab == null) { status(I18n.tr("agent.workspace.noConnectedTab")); return; }
        boolean current = Boolean.TRUE.equals(tab.getProperties().get(AUTO_LOW_RISK));
        setAutoApprove(tab, !current, "menu");
    }

    /** 参数补全：/resume 列任务、/model 列配置、/auto 列 on|off。 */
    private List<AgentConsoleView.ArgumentSuggestion> argumentSuggestions(String text) {
        String[] parts = text.stripLeading().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String partial = parts.length == 2 ? parts[1].toLowerCase(Locale.ROOT) : "";
        Tab tab = activeTab();
        switch (command) {
            case "/resume", "/continue" -> {
                List<AgentConversation> tasks = tab == null ? List.of()
                        : connectionTasks.getOrDefault(connectionKey(tab), List.of());
                List<AgentConsoleView.ArgumentSuggestion> result = new ArrayList<>();
                for (int index = 0; index < tasks.size(); index++) {
                    AgentConversation task = tasks.get(index);
                    String value = "/resume " + (index + 1);
                    if (partial.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(partial)
                            || task.displayTitle().toLowerCase(Locale.ROOT).contains(partial)) {
                        result.add(new AgentConsoleView.ArgumentSuggestion(
                                I18n.tr("agent.suggestion.task", index + 1, task.displayTitle()), value));
                    }
                }
                return result;
            }
            case "/model" -> {
                List<AIConfig> configs = AIConfigManager.getInstance().getAllConfigs();
                AIConfig current = AIConfigManager.getInstance().getCurrentConfig();
                List<AgentConsoleView.ArgumentSuggestion> result = new ArrayList<>();
                int index = 1;
                for (AIConfig config : configs) {
                    String value = "/model " + config.getName();
                    boolean active = current != null && config.getName().equals(current.getName());
                    if (partial.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(partial)
                            || config.getModel().toLowerCase(Locale.ROOT).contains(partial)) {
                        result.add(new AgentConsoleView.ArgumentSuggestion(
                                (active ? "● " : "  ") + config.getModel() + " · " + config.getName(), value));
                    }
                    index++;
                }
                return result;
            }
            case "/auto" -> {
                return List.of(
                        new AgentConsoleView.ArgumentSuggestion(I18n.tr("agent.workspace.on"), "/auto on"),
                        new AgentConsoleView.ArgumentSuggestion(I18n.tr("agent.workspace.off"), "/auto off"));
            }
            default -> { return List.of(); }
        }
    }

    private void receive(AgentActivityBus.Event event) {
        if (disposed) return;
        if (event.kind() == AgentActivityBus.Kind.PROGRESS) {
            // 进度只更新状态行，不进入历史、不污染回放记录
            Platform.runLater(() -> {
                if (disposed || console == null) return;
                Tab tab = activeTab();
                if (tab != null && event.sessionId().equals(tab.getId())) console.setStatus(event.text());
            });
            return;
        }
        Platform.runLater(() -> {
            if (disposed) return;
            String key = sessionHistoryKeys.getOrDefault(event.sessionId(), event.sessionId());
            List<AgentActivityBus.Event> events = history.computeIfAbsent(key, ignored -> new ArrayList<>());
            events.add(event);
            if (events.size() > 300) events.subList(0, events.size() - 300).clear();
            // 状态与待回答问题都先于显示过滤登记：标签切走期间到达的提问，切回后仍必须可回答
            AgentSessionTracker.getInstance().record(event);
            if (event.kind() == AgentActivityBus.Kind.SUCCESS
                    || event.kind() == AgentActivityBus.Kind.ERROR
                    || event.kind() == AgentActivityBus.Kind.CANCELLED) {
                persistHistory();
            }
            if (event.kind() == AgentActivityBus.Kind.ASK && event.answerHandler() != null) {
                pendingQuestions.put(event.sessionId(), event);
            }
            Tab tab = activeTab();
            if (tab == null || !event.sessionId().equals(tab.getId())) return;
            if (event.kind() != AgentActivityBus.Kind.USER) printEvent(tab, event);
            // 运行状态变化后同步切换器与菜单可用性，任务结束后无需再切标签才能换 AI。
            refreshConsoleControls(tab);
        });
    }

    /** owner 是产生该事件的会话标签；审批与“重新申请”都绑定它，不读取切换后的选中标签。 */
    private void printEvent(Tab owner, AgentActivityBus.Event event) {
        if(event.kind()==AgentActivityBus.Kind.STATUS && event.proposal()!=null) return;
        if (event.kind() == AgentActivityBus.Kind.PROPOSAL && event.approvalHandler() != null) {
            String proposal = event.text() == null ? "" : event.text().trim();
            int endRisk = proposal.startsWith("[") ? proposal.indexOf(']') : -1;
            String risk = endRisk > 1 ? proposal.substring(1, endRisk) : "待确认";
            String command = endRisk > 1 ? proposal.substring(endRisk + 1).trim() : proposal;
            // 审批计数按 proposalId 核销：历史已处理卡片重放不会扣减新提议的计数；
            // 命令只填入产生该提议的会话，目标失效时不回退到当前标签。
            var tracker = AgentSessionTracker.getInstance();
            console.printCommandProposal(command, risk, event.proposal(),
                    tracker.trackDecision(event.sessionId(), event.proposalId(), event.approvalHandler()),
                    cmd -> {
                        if (bridge != null && bridge.insertCommand().apply(event.sessionId(), cmd)) {
                            status(I18n.tr("agent.workspace.filled"));
                        } else {
                            status(I18n.tr("agent.workspace.targetInvalid"));
                        }
                    }, () -> {
                        if (owner == null) return;
                        if(event.proposal()!=null && event.proposal().targetValid()) {
                            newTaskForTab(owner,"");
                            console.prepareGoal(command);
                            status(I18n.tr("agent.approval.reapplyHint"));
                        } else newTaskForTab(owner, "");
                    });
            return;
        }
        switch (event.kind()) {
            case USER -> console.printUser(event.text());
            case RESPONSE -> console.printAssistant(event.text());
            case ASK -> {
                if (event.answerHandler() != null) {
                    console.printQuestion(event.text(), event.options());
                } else {
                    // 历史问题不再有回答回调；按普通记录展示，避免用户误以为还能作答。
                    console.printAssistant(historicalQuestion(event));
                }
            }
            case THINK -> console.printTechnical(AgentConsoleView.Role.THINK, event.text());
            case TOOL -> console.printTechnical(AgentConsoleView.Role.TOOL, event.text());
            case ERROR -> printErrorMaybeRecoverable(event);
            case SUCCESS -> console.printSuccess(event.text());
            case EXECUTION -> console.printStatus(event.text() == null || event.text().isBlank()
                    ? I18n.tr("agent.workspace.executing")
                    : I18n.tr("agent.workspace.executing") + " · " + event.text());
            case CANCELLED, STATUS -> console.printStatus(event.text());
            // PROPOSAL with an approval handler is rendered as a dedicated card above.
            case PROPOSAL -> console.printAssistant(event.text());
            // 进度在 receive() 中提前路由到状态行，不会走到这里。
            case PROGRESS -> console.printStatus(event.text());
        }
    }

    /** 失败可重试的错误：事件新鲜且服务端仍有可重试步骤时提供“重试上一步 / 切换模型”入口。 */
    private void printErrorMaybeRecoverable(AgentActivityBus.Event event) {
        boolean fresh = event.timestamp() != null
                && Duration.between(event.timestamp(), Instant.now()).getSeconds() < 120;
        boolean retryable = fresh && commandService != null && activeTab() != null
                && commandService.hasFailedAgentStep(activeTab());
        if (retryable) {
            console.printRecoverableError(event.text(), () -> {
                Tab tab = activeTab();
                if (tab != null && commandService != null) commandService.retryFailedAgentStep(tab);
            }, () -> status(I18n.tr("agent.error.switchModelHint")));
        } else {
            console.printError(event.text());
        }
    }

    private String historicalQuestion(AgentActivityBus.Event event) {
        StringBuilder text = new StringBuilder(event.text() == null ? "" : event.text());
        List<String> options = event.options();
        if (options != null && !options.isEmpty()) {
            for (int index = 0; index < options.size(); index++) {
                text.append("\r\n ").append(index + 1).append(". ").append(options.get(index));
            }
        }
        return text.toString();
    }

    private void render(Tab tab) {
        if (console == null) return;
        updateTerminalStatus(tab);
        console.clearMessages();
        if (tab == null || tab.getId() == null) {
            console.welcome();
            status(I18n.tr("agent.workspace.chooseTab"));
            return;
        }
        List<AgentActivityBus.Event> events = history.getOrDefault(historyKey(tab), List.of());
        if (events.isEmpty()) console.welcome();
        else for (AgentActivityBus.Event event : events) printEvent(tab, event);
        console.requestInputFocus();
    }

    private void updateTerminalStatus(Tab tab) {
        if (console == null) return;
        if (tab == null) {
            console.setTarget(I18n.tr("agent.workspace.chooseTab"));
            console.setTask(null);
            console.setStatus(I18n.tr("agent.workspace.idle"));
            refreshAiProfileSelector(null);
            return;
        }
        String taskId = String.valueOf(tab.getProperties().get(AGENT_TASK_ID));
        String task = connectionTasks.getOrDefault(connectionKey(tab), List.of()).stream()
                .filter(item -> item.id().equals(taskId))
                .map(AgentConversation::displayTitle)
                .findFirst().orElse(null);
        console.setTask(task);
        AIConfig current = AIConfigManager.getInstance().getCurrentConfig();
        // 选择器展示模型名；状态行保留配置/供应商名，避免两处显示同一信息。
        console.setStatus(current == null ? I18n.tr("agent.workspace.noModel") : current.getName());
        console.setTarget(I18n.tr("agent.workspace.target", readableTarget(tab)));
        refreshConsoleControls(tab);
    }

    /**
     * 可读目标优先取标签上登记的连接参数/基础标题，避免退化成内部 tab id；
     * 本地标签或无元数据时使用标签标题，仍不暴露 tab-1 这类身份。
     */
    private String readableTarget(Tab tab) {
        if (tab == null) return I18n.tr("agent.workspace.chooseTab");
        Object stored = tab.getProperties().get(TerminalPanelController.CONNECTION_PARAMS_KEY);
        if (stored instanceof ConnectionParams params) {
            String target = params.displayTarget();
            if (target != null && !target.isBlank()) return target;
        }
        Object base = tab.getProperties().get(TerminalPanelController.BASE_TITLE_KEY);
        if (base instanceof String baseTitle && !baseTitle.isBlank()) return baseTitle;
        String title = tab.getText();
        return title == null || title.isBlank() ? I18n.tr("agent.workspace.noTab") : title;
    }

    /**
     * 停止当前会话上运行的 Agent 任务（/stop、停止按钮、Esc 共用）。
     * 只作用于明确的任务目标：当前控制台所属的会话。其他会话的运行任务不受影响 ——
     * 停止全部应作为独立操作提供，不能让普通停止隐含执行全局扫描。
     * 取消后由业务侧发布 CANCELLED 事件，tracker 会核销该会话的待审批计数。
     */
    void stopAgent() {
        Tab tab = activeTab();
        if (tab == null || tab.getId() == null) {
            status(I18n.tr("agent.workspace.noConnectedTab"));
            return;
        }
        String sessionId = tab.getId();
        AgentSessionTracker tracker = AgentSessionTracker.getInstance();
        if (!tracker.isRunning(sessionId)) {
            pendingQuestions.remove(sessionId);
            boolean othersRunning = !tracker.runningSessions().isEmpty();
            status(othersRunning ? I18n.tr("agent.workspace.othersRunning")
                    : I18n.tr("agent.workspace.notRunning"));
            return;
        }
        pendingQuestions.remove(sessionId);
        if (bridge != null) {
            bridge.cancelSession().accept(sessionId);
        } else if (commandService != null) {
            commandService.cancelAIRequest(tab);
        }
    }

    private void clearCurrent() {
        Tab tab = activeTab();
        if (tab != null) {
            String key=historyKey(tab);
            var retained=history.getOrDefault(key,List.of()).stream().filter(event->
                    event.proposal()!=null && event.kind()==AgentActivityBus.Kind.PROPOSAL
                            && event.proposal().state()==com.runzhi.aishell.agent.AgentProposal.State.PENDING
                            || event==pendingQuestions.get(tab.getId())).toList();
            history.put(key,new ArrayList<>(retained));
            render(tab);
            return;
        }
        console.welcome();
    }

    private void status(String text) {
        if (console != null) console.printStatus(text);
    }

    private void error(String text) {
        if (console != null) console.printError(text);
    }
    private Tab activeTab() {
        Tab tab=activeTabSupplier.get();
        return tab!=null && "terminal-hint-tab".equals(tab.getId())?null:tab;
    }

    /** 审批定位：选中并显示目标标签；后续输入与停止仍以当前选中标签为准。 */
    public void focusApproval(Tab tab) {
        if(tab==null) return;
        showTab(tab);
        if(console!=null) console.focusPendingApproval();
    }
    private String historyKey(Tab tab) { return connectionKey(tab) + "::" + tab.getProperties().getOrDefault(AGENT_TASK_ID, "default"); }
    /** 标签上登记的连接参数是会话归属的第一来源；SSH 注册表只作为旧标签的兜底。 */
    private String connectionKey(Tab tab) {
        if (tab == null || tab.getId() == null) return "none";
        Object stored = tab.getProperties().get(TerminalPanelController.CONNECTION_PARAMS_KEY);
        if (stored instanceof ConnectionParams params) {
            String key = connectionIdentity(params);
            if (key != null) return key;
        }
        try {
            var ssh = com.runzhi.aishell.common.ai.AIShellModuleManager.getInstance().getSshService();
            String key = ssh == null ? null : connectionIdentity(ssh.getTabConnectionParams(tab.getId()));
            if (key != null) return key;
        } catch (Exception ignored) { }
        return tab.getId();
    }

    private static String connectionIdentity(ConnectionParams params) {
        if (params == null || params.getHost() == null) return null;
        return (params.getUsername() == null ? "" : params.getUsername()) + '@' + params.getHost() + ':' + params.getPort();
    }

    /** Release callbacks only when destroyed; hiding the Agent workspace keeps its task intact. */
    @Override public void onWindowClosing() {
        if (disposed) return;
        disposed = true;
        persistHistory();
        // 关闭时同步 flush 待写历史，最多等待 2 秒，避免丢失防抖窗口内的最后一次更新。
        historyWriter.shutdown();
        if (activitySubscription != null) {
            try { activitySubscription.close(); }
            catch (Exception failure) { throw new IllegalStateException("Unable to release Agent subscription", failure); }
            finally { activitySubscription = null; }
        }
        pendingQuestions.clear();
        history.clear();
        sessionHistoryKeys.clear();
        connectionTasks.clear();
        activeTabSupplier = () -> null;
        bridge = null;
        commandService = null;
    }

    /** Generated titles follow the display language; user-entered titles remain verbatim. */
    private record AgentConversation(String id, String title, int ordinal) {
        String displayTitle() { return title == null ? I18n.tr("agent.workspace.taskNumber", ordinal) : title; }
        @Override public String toString() { return displayTitle(); }
    }
}
