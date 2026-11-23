/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ui;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ClipboardUtils;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.text.HitInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Stable Claude-style Agent UI: scrollable transcript with a layout-anchored prompt. */
public final class AgentConsoleView extends BorderPane {
    public record Command(String name, String description) { }

    /** AI 模型选项：id 用于定位配置，label 是下拉框显示文本（模型名，冲突时附带配置名）。 */
    public record AiProfile(String id, String label) {
        @Override public String toString() { return label; }
    }

    /** Presentation roles come from the activity kind, never from guessing ANSI colors. */
    public enum Role { USER, ASSISTANT, QUESTION, STATUS, ERROR, SUCCESS, THINK, TOOL }

    /** 命令参数建议：display 是列表显示文本，value 是选中后填入输入框的完整命令。 */
    public record ArgumentSuggestion(String display, String value) { }

    /** “更多”菜单的动作回调；菜单项只负责发起，真正逻辑由控制器实现。 */
    public interface AgentMenuActions {
        void newTask();
        void showHistory();
        void showPermissions();
        void toggleAutoApprove();
        void clearTask();
        void deleteTask();
    }

    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final String MONO = "-fx-font-family: 'Cascadia Code', 'Consolas', monospace;";
    /** 距底部小于约一个消息卡高度时视为“在底部”，继续跟随新消息。 */
    private static final double NEAR_BOTTOM_THRESHOLD = 96;
    private static final String WELCOME_MARKER = "agentWelcome";
    // 消息卡之间的行间距：拉开距离便于区分不同来源的输出
    private final VBox transcript = new VBox(12);
    /** 当前拖选生效的消息（Ctrl+C 与右键复制的来源）；null 表示无选区。 */
    private MessageSelection activeSelection;
    private final ScrollPane scroll = new ScrollPane(transcript);
    private final TextArea input = new TextArea();
    private final ListView<String> suggestions = new ListView<>();
    private final Label status = new Label(I18n.tr("agent.idle"));
    private final Label taskLabel = new Label();
    private final Button historyButton = new Button(I18n.tr("agent.history.button"));
    private Runnable historyAction = () -> { };
    /** 「粘贴到终端执行」按钮的回调：把指令写入当前会话终端并执行（GitHub #11）。 */
    private Consumer<String> commandRunner = ignored -> { };
    private final ComboBox<AiProfile> aiSelector = new ComboBox<>();
    private boolean updatingAiProfiles;
    private Consumer<String> aiSelectionHandler = ignored -> { };
    private final Label targetLabel=new Label();
    /** 这些命令需要参数；点击建议时只填入并等待用户补充，其余命令直接执行。 */
    private static final Set<String> COMMANDS_WITH_ARGUMENTS = Set.of("/new", "/resume", "/model", "/auto");
    private final MenuButton moreMenu = new MenuButton(I18n.tr("agent.menu.more"));
    private final MenuItem newTaskItem = new MenuItem(I18n.tr("agent.menu.newTask"));
    private final MenuItem historyItem = new MenuItem(I18n.tr("agent.menu.history"));
    private final MenuItem permissionsItem = new MenuItem(I18n.tr("agent.menu.permissions"));
    private final CheckMenuItem autoApproveItem = new CheckMenuItem(I18n.tr("agent.menu.autoApprove"));
    private final MenuItem clearTaskItem = new MenuItem(I18n.tr("agent.menu.clearTask"));
    private final MenuItem deleteTaskItem = new MenuItem(I18n.tr("agent.menu.deleteTask"));
    private AgentMenuActions menuActions = new AgentMenuActions() {
        @Override public void newTask() { }
        @Override public void showHistory() { }
        @Override public void showPermissions() { }
        @Override public void toggleAutoApprove() { }
        @Override public void clearTask() { }
        @Override public void deleteTask() { }
    };
    private Function<String, List<ArgumentSuggestion>> argumentSuggestionProvider = ignored -> List.of();
    private final List<Command> commands;
    private final List<String> inputHistory = new ArrayList<>();
    private final Consumer<String> lineHandler;
    private final Runnable interruptHandler;
    private final Runnable clearHandler;
    private List<ArgumentSuggestion> currentArgumentSuggestions = List.of();
    private String lastAcceptedArgument;
    private int historyOffset;
    private int approvalIndex;
    private final Label approvalCount=new Label();
    private final Button previousApproval=new Button(I18n.tr("agent.approval.previous"));
    private final Button nextApproval=new Button(I18n.tr("agent.approval.next"));
    private final javafx.scene.layout.FlowPane approvalNavigation=new javafx.scene.layout.FlowPane(6,6);

    public AgentConsoleView(List<Command> commands, Consumer<String> lineHandler,
                            Runnable interruptHandler, Runnable clearHandler) {
        this.commands = List.copyOf(commands);
        this.lineHandler = lineHandler;
        this.interruptHandler = interruptHandler;
        this.clearHandler = clearHandler;
        build();
    }

    /** 使用全局主题颜色构建对话记录与固定输入区。 */
    private void build() {
        targetLabel.setWrapText(true); targetLabel.setMinWidth(0);
        targetLabel.setPadding(new Insets(7,10,7,10));
        targetLabel.getStyleClass().add("agent-target-label");
        targetLabel.setStyle("-fx-text-fill: -fx-text-primary; -fx-font-weight: bold;");
        taskLabel.setWrapText(true);
        taskLabel.setMinWidth(0);
        taskLabel.getStyleClass().add("agent-task-label");
        taskLabel.setStyle("-fx-text-fill: -fx-text-secondary; -fx-font-size: 11px;");
        taskLabel.setVisible(false);
        taskLabel.setManaged(false);
        taskLabel.setMaxWidth(Double.MAX_VALUE);
        historyButton.setFocusTraversable(false);
        historyButton.getStyleClass().add("agent-history-button");
        historyButton.setOnAction(event -> historyAction.run());
        newTaskItem.setOnAction(event -> menuActions.newTask());
        historyItem.setOnAction(event -> menuActions.showHistory());
        permissionsItem.setOnAction(event -> menuActions.showPermissions());
        autoApproveItem.setOnAction(event -> menuActions.toggleAutoApprove());
        clearTaskItem.setOnAction(event -> menuActions.clearTask());
        deleteTaskItem.setOnAction(event -> menuActions.deleteTask());
        moreMenu.setFocusTraversable(false);
        moreMenu.getStyleClass().add("agent-more-menu");
        moreMenu.getItems().setAll(newTaskItem, historyItem, permissionsItem,
                autoApproveItem, clearTaskItem, deleteTaskItem);
        HBox taskRow = new HBox(8, taskLabel, historyButton, moreMenu);
        taskRow.setAlignment(Pos.CENTER_LEFT);
        taskRow.setMinWidth(0);
        taskRow.setPadding(new Insets(0, 10, 7, 10));
        HBox.setHgrow(taskLabel, Priority.ALWAYS);
        setTop(new VBox(targetLabel, taskRow));
        setMinWidth(0);
        setStyle("-fx-background-color: -fx-code-bg;");
        transcript.setPadding(new Insets(14));
        transcript.setStyle("-fx-background-color: -fx-code-bg;");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: -fx-code-bg; -fx-background-color: -fx-code-bg;");
        setCenter(scroll);

        Label prompt = new Label("❯");
        prompt.setStyle(MONO + " -fx-text-fill: -fx-success-color; -fx-font-size: 15px; -fx-font-weight: bold;");
        input.setPromptText(I18n.tr("agent.prompt"));
        input.setWrapText(true);
        input.setMinWidth(0);
        input.setPrefRowCount(2);
        input.setMinHeight(48);
        input.setMaxHeight(110);
        input.setStyle(MONO + " -fx-control-inner-background: -fx-code-bg; -fx-text-fill: -fx-text-primary;"
                + " -fx-prompt-text-fill: -fx-text-disabled; -fx-background-color: transparent; -fx-border-color: transparent;"
                + " -fx-highlight-fill: -fx-primary-color; -fx-highlight-text-fill: white;");
        HBox inputRow = new HBox(8, prompt, input);
        inputRow.setAlignment(Pos.TOP_LEFT);
        inputRow.setPadding(new Insets(7, 10, 5, 10));
        inputRow.setStyle("-fx-background-color: -fx-code-bg; -fx-border-color: -fx-text-disabled;"
                + " -fx-border-radius: 7; -fx-background-radius: 7;");
        HBox.setHgrow(input, Priority.ALWAYS);

        suggestions.setVisible(false);
        suggestions.setManaged(false);
        suggestions.setFocusTraversable(false);
        suggestions.setMaxHeight(216);
        suggestions.setStyle(MONO + " -fx-control-inner-background: -fx-panel-bg; -fx-background-color: -fx-panel-bg;"
                + " -fx-border-color: -fx-border-default;");
        suggestions.setOnMouseClicked(event -> acceptSuggestion());

        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.getStyleClass().add("agent-status-label");
        status.setStyle(MONO + " -fx-text-fill: -fx-text-secondary; -fx-font-size: 12px;");
        aiSelector.setMinWidth(96);
        aiSelector.setPrefWidth(150);
        aiSelector.setMaxWidth(200);
        aiSelector.setFocusTraversable(true);
        aiSelector.getStyleClass().add("agent-ai-selector");
        aiSelector.setAccessibleText(I18n.tr("agent.workspace.switchAi"));
        aiSelector.setTooltip(new Tooltip(I18n.tr("agent.workspace.switchAi")));
        aiSelector.valueProperty().addListener((ignored, previous, selected) -> {
            if (updatingAiProfiles || selected == null || selected.equals(previous)) return;
            aiSelectionHandler.accept(selected.id());
        });
        previousApproval.setOnAction(e->moveApproval(-1));
        nextApproval.setOnAction(e->moveApproval(1));
        previousApproval.getStyleClass().add("ai-command-btn"); nextApproval.getStyleClass().add("ai-command-btn");
        approvalNavigation.getChildren().setAll(approvalCount,previousApproval,nextApproval);
        approvalNavigation.setVisible(false); approvalNavigation.setManaged(false);
        Label aiLabel = new Label("AI");
        aiLabel.setStyle(MONO + " -fx-text-fill: -fx-text-secondary; -fx-font-size: 11px;");
        HBox statusRow = new HBox(6, aiLabel, aiSelector, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setMinWidth(0);
        HBox.setHgrow(status, Priority.ALWAYS);
        VBox bottom = new VBox(5, approvalNavigation, suggestions, inputRow, statusRow);
        bottom.setPadding(new Insets(5, 10, 9, 10));
        bottom.setStyle("-fx-background-color: -fx-code-bg;");
        setBottom(bottom);

        input.textProperty().addListener((ignored, oldValue, value) -> updateSuggestions(value));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        // 转录区有拖选选区时，Ctrl+C（macOS Cmd+C）复制选区；否则交给输入框等默认行为。
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                MessageSelection selection = activeSelection;
                if (selection != null && selection.hasRange()) {
                    copySelection(selection);
                    event.consume();
                }
            }
        });
    }

    private void handleKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) { event.consume(); interruptHandler.run(); return; }
        if (event.isControlDown() && event.getCode() == KeyCode.L) { event.consume(); clearHandler.run(); return; }
        if (suggestions.isVisible() && (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN)) {
            event.consume();
            int count = suggestions.getItems().size();
            if (count == 0) return;
            int selected = Math.max(0, suggestions.getSelectionModel().getSelectedIndex());
            suggestions.getSelectionModel().select(event.getCode() == KeyCode.DOWN
                    ? Math.min(count - 1, selected + 1) : Math.max(0, selected - 1));
            return;
        }
        if (event.getCode() == KeyCode.TAB && suggestions.isVisible()) { event.consume(); acceptSuggestion(); return; }
        if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
            event.consume();
            String value = input.getText().trim();
            if (suggestions.isVisible() && !isCompleteCommand(value)) { acceptSuggestion(); return; }
            submit(value);
            return;
        }
        if (!suggestions.isVisible() && !input.getText().contains("\n")) navigateHistory(event);
    }

    private void navigateHistory(KeyEvent event) {
        if (inputHistory.isEmpty()) return;
        if (event.getCode() == KeyCode.UP && input.getCaretPosition() == 0) {
            event.consume(); historyOffset = Math.min(inputHistory.size(), historyOffset + 1);
        } else if (event.getCode() == KeyCode.DOWN && input.getCaretPosition() == input.getLength()) {
            event.consume(); historyOffset = Math.max(0, historyOffset - 1);
        } else return;
        input.setText(historyOffset == 0 ? "" : inputHistory.get(inputHistory.size() - historyOffset));
        input.positionCaret(input.getLength());
    }

    private void submit(String value) {
        if (value.isBlank()) return;
        inputHistory.remove(value);
        inputHistory.add(value);
        if (inputHistory.size() > 100) inputHistory.remove(0);
        historyOffset = 0;
        input.clear();
        showSuggestions(false);
        printUser(value);
        // 用户主动发送：无论之前滚到哪，都拉回最底部，便于紧跟 AI 的后续输出
        jumpToBottom();
        lineHandler.accept(value);
    }

    private boolean isCompleteCommand(String value) {
        return commands.stream().anyMatch(command -> command.name().equalsIgnoreCase(value));
    }

    private void updateSuggestions(String value) {
        String typed = value == null ? "" : value.stripLeading().toLowerCase(Locale.ROOT);
        if (!typed.startsWith("/") || typed.contains("\n")) { showSuggestions(false); return; }
        if (value.trim().equals(lastAcceptedArgument)) { showSuggestions(false); return; }
        if (!typed.contains(" ")) {
            List<String> matches = commands.stream().filter(command -> command.name().startsWith(typed))
                    .map(command -> String.format("%-14s %s", command.name(), command.description())).toList();
            currentArgumentSuggestions = List.of();
            suggestions.getItems().setAll(matches);
            showSuggestions(!matches.isEmpty());
            if (!matches.isEmpty()) suggestions.getSelectionModel().selectFirst();
            return;
        }
        String command = typed.split("\\s+", 2)[0];
        if (!COMMANDS_WITH_ARGUMENTS.contains(command)) { showSuggestions(false); return; }
        // 命令名加空格后给出参数候选：/resume 列任务、/model 列配置、/auto 列 on|off。
        List<ArgumentSuggestion> arguments = argumentSuggestionProvider.apply(value.strip());
        currentArgumentSuggestions = arguments == null ? List.of() : List.copyOf(arguments);
        suggestions.getItems().setAll(currentArgumentSuggestions.stream()
                .map(suggestion -> String.format("%-20s %s", suggestion.value(), suggestion.display()))
                .toList());
        showSuggestions(!currentArgumentSuggestions.isEmpty());
        if (!currentArgumentSuggestions.isEmpty()) suggestions.getSelectionModel().selectFirst();
    }

    private void acceptSuggestion() {
        String selected = suggestions.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (!currentArgumentSuggestions.isEmpty()) {
            // 参数候选：填入完整命令（如 /resume 3），由用户检查后回车执行。
            int index = suggestions.getSelectionModel().getSelectedIndex();
            ArgumentSuggestion suggestion = currentArgumentSuggestions.get(index);
            lastAcceptedArgument = suggestion.value();
            input.setText(suggestion.value());
            input.positionCaret(input.getLength());
            input.requestFocus();
            showSuggestions(false);
            return;
        }
        String command = selected.stripLeading().split("\\s+", 2)[0];
        showSuggestions(false);
        if (COMMANDS_WITH_ARGUMENTS.contains(command)) {
            input.setText(command + " ");
            input.positionCaret(input.getLength());
            input.requestFocus();
        } else {
            // 无参数命令点击后直接执行，避免“点了建议还要再按一次 Enter”的半成品体验。
            input.clear();
            submit(command);
        }
    }

    private void showSuggestions(boolean visible) {
        suggestions.setVisible(visible);
        suggestions.setManaged(visible);
        if (visible) suggestions.setPrefHeight(Math.min(216, 8 + suggestions.getItems().size() * 25));
    }

    public void welcome() {
        clearMessages();
        Label title = new Label("AI Shell Agent");
        title.setStyle("-fx-text-fill: -fx-ai-color; -fx-font-size: 16px; -fx-font-weight: bold;");
        Label hint = new Label(I18n.tr("agent.welcome"));
        hint.setWrapText(true);
        hint.setMinWidth(0);
        hint.setStyle("-fx-text-fill: -fx-text-secondary;");
        title.getProperties().put(WELCOME_MARKER, true);
        hint.getProperties().put(WELCOME_MARKER, true);
        transcript.getChildren().addAll(title, hint);
        input.requestFocus();
    }

    public void clearMessages() {
        transcript.getChildren().clear();
        approvalIndex = 0;
        updateApprovalNavigation();
        scroll.setVvalue(0);
    }

    /** Compatibility for direct help/status output; normal business paths use typed methods below. */
    public void print(String ansiText) {
        appendMessage(Role.STATUS, ANSI.matcher(ansiText == null ? "" : ansiText).replaceAll(""), List.of());
    }

    public void printUser(String text) {
        appendMessage(Role.USER, text, List.of());
    }

    public void printAssistant(String text) {
        appendMessage(Role.ASSISTANT, text, List.of());
    }

    public void printQuestion(String text, List<String> options) {
        appendMessage(Role.QUESTION, text, options);
    }

    public void printStatus(String text) {
        appendMessage(Role.STATUS, text, List.of());
    }

    public void printError(String text) {
        appendMessage(Role.ERROR, text, List.of());
    }

    /** 可恢复的错误卡片：提供“重试上一步 / 切换模型 / 复制错误”入口，避免用户重新输入目标。 */
    public void printRecoverableError(String text, Runnable retry, Runnable switchModel) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return;
        removeWelcome();
        Label roleLabel = new Label(I18n.tr("agent.role.error"));
        roleLabel.getStyleClass().addAll("agent-message-role", "agent-role-error");
        roleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(roleLabel, Priority.ALWAYS);
        Button copy = new Button(I18n.tr("agent.copy"));
        copy.setFocusTraversable(false);
        copy.getStyleClass().add("ai-command-btn");
        copy.setOnAction(event -> setStatus(I18n.tr(
                ClipboardUtils.copyText(normalized) ? "agent.copied" : "agent.copyFailed")));
        HBox header = new HBox(6, roleLabel, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        StackPane body = selectableTextFlow(normalized, false, Role.ERROR);
        Button retryButton = new Button(I18n.tr("agent.error.retry"));
        retryButton.getStyleClass().add("ai-command-btn");
        retryButton.setOnAction(event -> { retryButton.setDisable(true); retry.run(); });
        Button switchButton = new Button(I18n.tr("agent.error.switchModel"));
        switchButton.getStyleClass().add("ai-command-btn");
        switchButton.setOnAction(event -> {
            aiSelector.setDisable(false);
            aiSelector.requestFocus();
            setStatus(I18n.tr("agent.error.switchModelHint"));
            switchModel.run();
        });
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(6, 6, retryButton, switchButton);
        VBox card = new VBox(7, header, body, actions);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.getStyleClass().addAll("agent-message", "agent-message-error");
        transcript.getChildren().add(card);
        // 可恢复错误带操作按钮：必须让用户看到，无条件拉回底部
        javafx.application.Platform.runLater(this::jumpToBottom);
    }

    public void printSuccess(String text) {
        appendMessage(Role.SUCCESS, text, List.of());
    }

    /** Thinking/tool output is monospace and only collapsed when it is genuinely long. */
    public void printTechnical(Role role, String text) {
        Role actual = role == Role.THINK || role == Role.TOOL ? role : Role.STATUS;
        appendMessage(actual, text, List.of());
    }

    private void appendMessage(Role role, String rawText, List<String> options) {
        String text = normalize(rawText);
        if (text.isBlank()) return;
        removeWelcome();
        boolean follow = isNearBottom();
        double followValue = pixelOffset();
        String roleClass = role.name().toLowerCase(Locale.ROOT);

        VBox block = new VBox(4);
        block.setMinWidth(0);
        block.setMaxWidth(Double.MAX_VALUE);
        block.getStyleClass().addAll("agent-message", "agent-message-" + roleClass);

        String roleKey = role == Role.THINK ? "agent.role.thinking" : "agent.role." + roleClass;
        Label roleLabel = new Label(I18n.tr(roleKey));
        roleLabel.setMinWidth(0);
        roleLabel.setMaxWidth(Double.MAX_VALUE);
        roleLabel.getStyleClass().addAll("agent-message-role", "agent-role-" + roleClass);
        Button copy = new Button(I18n.tr("agent.copy"));
        copy.setFocusTraversable(false);
        copy.getStyleClass().add("agent-copy-button");
        HBox header = new HBox(6, roleLabel, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(roleLabel, Priority.ALWAYS);

        VBox body = new VBox(3);
        body.setMinWidth(0);
        String copyText = text;
        if (role == Role.QUESTION) {
            body.getChildren().add(selectableTextFlow(text, false, role));
            List<String> renderedOptions = new ArrayList<>();
            if (options != null) {
                for (String option : options) {
                    String normalized = normalize(option);
                    if (!normalized.isBlank()) renderedOptions.add(normalized);
                }
            }
            // Some events already embed their numbered options; do not print the same list twice.
            boolean optionsAlreadyInText = !renderedOptions.isEmpty()
                    && renderedOptions.stream().allMatch(option -> text.contains(option));
            if (!renderedOptions.isEmpty() && !optionsAlreadyInText) {
                StringBuilder numbered = new StringBuilder(text);
                for (int i = 0; i < renderedOptions.size(); i++) {
                    Label option = new Label((i + 1) + ".  " + renderedOptions.get(i));
                    option.setWrapText(true);
                    option.setMinWidth(0);
                    option.setMaxWidth(Double.MAX_VALUE);
                    option.getStyleClass().add("agent-message-option");
                    body.getChildren().add(option);
                    numbered.append('\n').append(i + 1).append(".  ").append(renderedOptions.get(i));
                }
                copyText = numbered.toString();
            }
        } else if (role == Role.THINK || role == Role.TOOL) {
            appendTechnicalBody(body, text, role);
        } else {
            body.getChildren().add(selectableTextFlow(text, false, role));
        }
        final String copyValue = copyText;
        copy.setOnAction(event -> copyMessage(copyValue));
        block.getChildren().addAll(header, body);
        // 指令类回复：附「粘贴到终端执行」按钮，一键把命令送进当前会话终端（GitHub #11）
        if (role == Role.ASSISTANT && looksLikeCommand(text)) {
            Button runInTerminal = new Button(I18n.tr("agent.reply.runInTerminal"));
            runInTerminal.setFocusTraversable(false);
            runInTerminal.getStyleClass().add("ai-command-btn");
            runInTerminal.setTooltip(new Tooltip(I18n.tr("agent.reply.runInTerminal.tip")));
            final String command = text;
            runInTerminal.setOnAction(event -> commandRunner.accept(command));
            HBox replyActions = new HBox(6, runInTerminal);
            block.getChildren().add(replyActions);
        }
        transcript.getChildren().add(block);
        scrollIfFollow(follow, followValue);
    }

    /**
     * 判断回复正文是否像一条单行 Shell 指令：单行、无中文、无句末标点、
     * 首词为命令式 token（字母数字下划线连字符，可带参数）。
     */
    public static boolean looksLikeCommand(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200 || trimmed.contains("\n")) {
            return false;
        }
        if (trimmed.codePoints().anyMatch(ch -> ch >= 0x3000 && ch <= 0x9FFF)) {
            return false;   // 含 CJK（含中文标点），按说明文字处理
        }
        for (char ending : new char[]{'.', '?', '!', '。', '？', '！'}) {
            if (trimmed.charAt(trimmed.length() - 1) == ending) {
                return false;   // 句末标点按自然语言处理
            }
        }
        return trimmed.matches("^[A-Za-z][A-Za-z0-9_@%:./-]*(\\s+\\S.*)?$");
    }

    /** TextFlow 与其唯一文本节点，供选择逻辑定位字符偏移。 */
    private record FlowText(TextFlow flow, Text text) { }

    private FlowText textFlow(String text, boolean code, Role role) {
        TextFlow flow = new TextFlow();
        // 行内行间距加大，长文本更易读
        flow.setLineSpacing(5);
        flow.setMinWidth(0);
        flow.setMaxWidth(Double.MAX_VALUE);
        Text node = new Text(text);
        node.getStyleClass().add(code ? "agent-message-code" : "agent-message-text");
        node.getStyleClass().add("agent-message-text-" + role.name().toLowerCase(Locale.ROOT));
        flow.getChildren().add(node);
        return new FlowText(flow, node);
    }

    /**
     * 消息正文：TextFlow 保持原有呈现，外层 StackPane 叠加选区高亮层，
     * 支持鼠标拖选、Ctrl+C（macOS Cmd+C）与右键复制片段。
     */
    private StackPane selectableTextFlow(FlowText body) {
        StackPane box = new StackPane();
        box.getStyleClass().add("agent-message-flow");
        Path highlight = new Path();
        highlight.getStyleClass().add("agent-selection-highlight");
        highlight.setManaged(false);
        highlight.setVisible(false);
        box.getChildren().addAll(highlight, body.flow());
        installSelection(new MessageSelection(body, highlight));
        return box;
    }

    private StackPane selectableTextFlow(String text, boolean code, Role role) {
        return selectableTextFlow(textFlow(text, code, role));
    }

    /** 一条消息的拖选状态：anchor/caret 为字符偏移，anchor == caret 表示无选区。 */
    private static final class MessageSelection {
        final TextFlow flow;
        final Text text;
        final Path highlight;
        int anchor = -1;
        int caret = -1;
        boolean dragging;
        MessageSelection(FlowText body, Path highlight) {
            this.flow = body.flow();
            this.text = body.text();
            this.highlight = highlight;
        }
        boolean hasRange() { return anchor >= 0 && caret >= 0 && anchor != caret; }
        int from() { return Math.min(anchor, caret); }
        int to() { return Math.max(anchor, caret); }
        String selectedText() {
            String value = text.getText();
            return value.substring(Math.max(0, from()), Math.min(value.length(), to()));
        }
    }

    private void installSelection(MessageSelection selection) {
        TextFlow flow = selection.flow;
        flow.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            clearActiveSelection();
            int end = selection.text.getText().length();
            HitInfo hit = hitSelectionPoint(selection.flow, event);
            selection.anchor = hit == null ? 0 : Math.min(hit.getInsertionIndex(), end);
            selection.caret = selection.anchor;
            selection.dragging = true;
            refreshHighlight(selection);
            event.consume();
        });
        flow.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!selection.dragging) return;
            int end = selection.text.getText().length();
            HitInfo hit = hitSelectionPoint(selection.flow, event);
            selection.caret = hit == null ? end : Math.min(hit.getInsertionIndex(), end);
            refreshHighlight(selection);
            event.consume();
        });
        flow.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!selection.dragging) return;
            selection.dragging = false;
            if (selection.hasRange()) activeSelection = selection;
            else clearActiveSelection();
            event.consume();
        });
        flow.setOnContextMenuRequested(event -> {
            MenuItem copy = new MenuItem(I18n.tr("agent.copy"));
            copy.setDisable(!selection.hasRange());
            copy.setOnAction(action -> copySelection(selection));
            ContextMenu menu = new ContextMenu(copy);
            menu.show(flow, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        // 高亮层坐标跟随 TextFlow 在 StackPane 内的实际位置（fixed: unmanaged 节点不参与布局）。
        selection.highlight.layoutXProperty().bind(flow.layoutXProperty());
        selection.highlight.layoutYProperty().bind(flow.layoutYProperty());
    }

    /** 事件坐标转 TextFlow 本地坐标；flow 未挂 Scene 时回退到事件本地坐标。 */
    private HitInfo hitSelectionPoint(TextFlow flow, MouseEvent event) {
        Point2D point = null;
        if (flow.getScene() != null) point = flow.screenToLocal(event.getScreenX(), event.getScreenY());
        if (point == null) point = new Point2D(event.getX(), event.getY());
        try { return flow.hitTest(point); }
        catch (RuntimeException ignored) { return null; }
    }

    private void clearActiveSelection() {
        MessageSelection previous = activeSelection;
        activeSelection = null;
        if (previous != null) {
            previous.anchor = -1;
            previous.caret = -1;
            refreshHighlight(previous);
        }
    }

    private void refreshHighlight(MessageSelection selection) {
        Path highlight = selection.highlight;
        if (highlight == null) return;
        if (!selection.hasRange()) {
            highlight.getElements().clear();
            highlight.setVisible(false);
            return;
        }
        String value = selection.text.getText();
        int from = Math.max(0, selection.from());
        int to = Math.min(value.length(), selection.to());
        if (from >= to) {
            highlight.getElements().clear();
            highlight.setVisible(false);
            return;
        }
        highlight.getElements().setAll(selection.flow.rangeShape(from, to));
        highlight.setVisible(true);
    }

    private void copySelection(MessageSelection selection) {
        if (selection == null || !selection.hasRange()) return;
        setStatus(I18n.tr(ClipboardUtils.copyText(selection.selectedText()) ? "agent.copied" : "agent.copyFailed"));
    }

    /** 当前拖选的消息文本（无选区返回 null）；供测试与辅助功能验证。 */
    public String getSelectedMessageText() {
        return activeSelection != null && activeSelection.hasRange() ? activeSelection.selectedText() : null;
    }

    private void appendTechnicalBody(VBox body, String text, Role role) {
        FlowText rendered = textFlow(text, true, role);
        if (text.length() > 240 || text.lines().count() > 4) {
            TitledPane details = new TitledPane(I18n.tr("agent.message.details"), selectableTextFlow(rendered));
            details.setAnimated(false);
            details.setExpanded(false);
            details.setMaxWidth(Double.MAX_VALUE);
            details.getStyleClass().add("agent-message-details");
            body.getChildren().add(details);
        } else {
            body.getChildren().add(selectableTextFlow(rendered));
        }
    }

    private void copyMessage(String text) {
        setStatus(I18n.tr(ClipboardUtils.copyText(text) ? "agent.copied" : "agent.copyFailed"));
    }

    private void removeWelcome() {
        transcript.getChildren().removeIf(node -> Boolean.TRUE.equals(node.getProperties().get(WELCOME_MARKER)));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return ANSI.matcher(value).replaceAll("")
                .replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    private boolean isNearBottom() {
        double viewport = scroll.getViewportBounds().getHeight();
        double content = transcript.getLayoutBounds().getHeight();
        if (viewport <= 0 || content <= viewport) return true;
        double offset = scroll.getVvalue() * (content - viewport);
        // 阈值取约一个消息卡的高度：内容刚超出视口、用户尚无处可滚时
        // vvalue 仍会被钳制为 0，窄阈值会把这种过渡态误判为“用户在顶部”而中断跟随。
        return content - viewport - offset <= NEAR_BOTTOM_THRESHOLD;
    }

    private void scrollIfFollow(boolean follow, double capturedOffsetPixels) {
        if (!follow) return;
        javafx.application.Platform.runLater(() -> {
            // 像素锚定：内容增长时视口停留在原内容像素位置，vvalue 分数随之下降，
            // 但用户并未滚动；只有像素偏移相对追加前变小才是用户主动上滚。
            double currentOffset = pixelOffset();
            if (currentOffset + 2 < capturedOffsetPixels) return;   // 用户已上滚 → 不覆盖
            // vvalue=1.0 只有在内容布局完成、滚动范围确定之后才对应底部：
            // 新内容挂载后范围仍是旧值，提前设置会被锚定抹掉，表现为停在半路。
            // 因此先同步应用样式与布局，再定位，确保落在最底部。
            scroll.applyCss();
            scroll.layout();
            scroll.setVvalue(1.0);
        });
    }

    /** 视口顶端在内容中的像素位置（范围 0 时为 0）；用于区分像素锚定与用户滚动。 */
    private double pixelOffset() {
        double content = transcript.getLayoutBounds().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        if (content <= viewport) return 0;
        return scroll.getVvalue() * (content - viewport);
    }

    /**
     * 强制定位到转录区最底部。
     * <p>
     * 用户发送消息、出现待审批卡片或可重试错误等「必须看到最新内容」的场景使用：
     * 不受 isNearBottom 约束，避免用户上滚阅读历史时错过审批与后续输出。
     */
    public void jumpToBottom() {
        scroll.applyCss();
        scroll.layout();
        scroll.setVvalue(1.0);
    }

    /** Renders a proposed shell command with copy and explicit approval actions. */
    public void printCommandProposal(String command, String risk, Consumer<Boolean> decision,
                                    Consumer<String> commandInserter) {
        printCommandProposal(command,risk,null,decision,commandInserter,()->{});
    }

    public void printCommandProposal(String command, String risk, com.runzhi.aishell.agent.AgentProposal proposal,
                                    Consumer<Boolean> decision, Consumer<String> commandInserter, Runnable restart) {
        Label heading = new Label(I18n.tr("agent.proposal", riskLabel(risk)));
        heading.setWrapText(true);
        heading.setMinWidth(0);
        heading.getStyleClass().addAll("ai-command-title", "ai-command-risk");
        Label code = new Label(command);
        code.setWrapText(true);
        code.setMinWidth(0);
        code.setMaxWidth(Double.MAX_VALUE);
        code.getStyleClass().add("ai-command-text");
        Button copy = new Button(I18n.tr("agent.copy"));
        copy.getStyleClass().add("ai-command-btn");
        copy.setOnAction(event -> setStatus(I18n.tr(
                ClipboardUtils.copyText(command) ? "agent.copied" : "agent.copyFailed")));
        Button send = new Button(I18n.tr("agent.send"));
        send.getStyleClass().add("ai-command-btn");
        // 是否真的填入由会话桥接决定，成功/失效说明由控制器回写，视图不预先宣称成功
        send.setOnAction(event -> {
            if(proposal==null || (proposal.targetValid() && proposal.state()!=com.runzhi.aishell.agent.AgentProposal.State.INVALID))
                commandInserter.accept(command);
        });
        Button approve = new Button(I18n.tr("agent.execute"));
        approve.getStyleClass().add("ai-command-btn");
        Button reject = new Button(I18n.tr("agent.reject"));
        reject.getStyleClass().addAll("ai-command-btn", "agent-reject-button");
        Consumer<Boolean> decideOnce = approved -> {
            approve.setDisable(true);
            reject.setDisable(true);
            decision.accept(approved);
        };
        approve.setOnAction(event -> {
            boolean confirmed = true;
            if ("高风险".equals(risk)) {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        I18n.tr("agent.highWarning") + "\n\n" + command,
                        ButtonType.CANCEL, ButtonType.OK);
                alert.setTitle(I18n.tr("agent.highTitle"));
                alert.setHeaderText(I18n.tr("agent.highHeader"));
                ((Button) alert.getDialogPane().lookupButton(ButtonType.OK)).setText(I18n.tr("agent.execute"));
                ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setText(I18n.tr("settings.cancel"));
                confirmed = alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
            }
            if(confirmed) decideOnce.accept(true);
        });
        reject.setOnAction(event -> decideOnce.accept(false));
        reject.setTooltip(new Tooltip(I18n.tr("agent.reject.tooltip")));
        // 拒绝靠右并红色醒目：主操作单独一排（窄宽可换行不截断），拒绝独占下一排并推到最右；
        // 与执行行拉开 10px 竖直间距，避免点执行时误中拒绝（GitHub #5）。
        javafx.scene.layout.FlowPane primary = new javafx.scene.layout.FlowPane(6, 6, copy, send, approve);
        primary.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox rejectRow = new HBox(6, spacer, reject);
        rejectRow.setAlignment(Pos.CENTER_LEFT);
        rejectRow.setMaxWidth(Double.MAX_VALUE);
        VBox actions = new VBox(10, primary, rejectRow);
        VBox card = new VBox(7, heading, code, actions);
        if(proposal!=null) {
            var context=proposal.context();
            Label metadata=new Label(I18n.tr("agent.approval.context", context.task(), proposal.id(),
                    context.user(),context.host(),proposal.sessionId(),context.directory(),context.impact()));
            metadata.setWrapText(true); metadata.setMinWidth(0);
            Label state=new Label(); state.setWrapText(true); state.setMinWidth(0);
            Label safety=new Label(I18n.tr("agent.highWarning")); safety.setWrapText(true); safety.setMinWidth(0);
            Button restartButton=new Button(); restartButton.getStyleClass().add("ai-command-btn");
            restartButton.setOnAction(e->restart.run());
            ((javafx.scene.layout.FlowPane) actions.getChildren().get(0)).getChildren().add(restartButton);
            Runnable refresh=()->{
                var current=proposal.state();
                boolean pending=current==com.runzhi.aishell.agent.AgentProposal.State.PENDING;
                boolean invalid=current==com.runzhi.aishell.agent.AgentProposal.State.INVALID || !proposal.targetValid();
                approve.setDisable(!pending); reject.setDisable(!pending); send.setDisable(invalid);
                restartButton.setText(I18n.tr(invalid?"agent.approval.newTask":"agent.approval.reapply"));
                boolean restartable=invalid || current==com.runzhi.aishell.agent.AgentProposal.State.EXPIRED;
                restartButton.setVisible(restartable); restartButton.setManaged(restartable);
                state.setText(I18n.tr("agent.approval.state."+current.name())+" · "+I18n.tr("agent.approval.deadline",proposal.deadline().toString()));
                card.getProperties().put("approvalPending",pending);
                updateApprovalNavigation();
            };
            refresh.run();
            javafx.animation.Timeline timer=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),e->refresh.run()));
            timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
            card.sceneProperty().addListener((_,_,scene)->{if(scene==null)timer.stop();else{refresh.run();timer.play();}});
            card.getChildren().add(1,metadata); card.getChildren().add(state);
            if("高风险".equals(risk)) card.getChildren().add(safety);
            card.setId("proposal-"+proposal.id());
        }
        card.setMinWidth(0);
        card.getStyleClass().add("ai-command-card");
        removeWelcome();
        transcript.getChildren().add(card);
        updateApprovalNavigation();
        // 待审批卡片阻塞任务：无论用户当前滚到哪，都拉回底部展示
        javafx.application.Platform.runLater(this::jumpToBottom);
    }

    /** Locate a pending card even after its target's transcript is rendered again. */
    public void focusPendingApproval() {
        approvalIndex=0;
        moveApproval(0);
    }

    private void updateApprovalNavigation() {
        int size=(int)transcript.getChildren().stream().filter(n->Boolean.TRUE.equals(n.getProperties().get("approvalPending"))).count();
        approvalIndex=Math.max(0,Math.min(approvalIndex,size-1));
        approvalCount.setText(I18n.tr("agent.approval.count",size));
        approvalNavigation.setVisible(size>0); approvalNavigation.setManaged(size>0);
        previousApproval.setDisable(approvalIndex==0); nextApproval.setDisable(approvalIndex>=size-1);
    }

    private void moveApproval(int step) {
        javafx.application.Platform.runLater(()->{
            var pending=transcript.getChildren().stream().filter(n->Boolean.TRUE.equals(n.getProperties().get("approvalPending"))).toList();
            if(pending.isEmpty()) return;
            approvalIndex=Math.max(0,Math.min(pending.size()-1,approvalIndex+step));
            updateApprovalNavigation();
            var node=pending.get(approvalIndex);
            double overflow=transcript.getHeight()-scroll.getViewportBounds().getHeight();
            scroll.setVvalue(overflow<=0?0:Math.max(0,Math.min(1,node.getBoundsInParent().getMinY()/overflow)));
            node.setFocusTraversable(true); node.requestFocus();
        });
    }

    /** Translate presentation only; approval checks keep the original risk metadata. */
    private String riskLabel(String risk) {
        if (risk == null) return "";
        return switch (risk) {
            case "高风险" -> I18n.tr("agent.risk.high");
            case "中风险" -> I18n.tr("agent.risk.medium");
            case "低风险" -> I18n.tr("agent.risk.low");
            case "只读" -> I18n.tr("agent.risk.readonly");
            default -> risk;
        };
    }

    public void clear() { welcome(); }
    public void setStatus(String value) { status.setText(value == null ? "" : value); }
    public void setTarget(String value) { targetLabel.setText(value); }

    /** 目标下方的当前任务名称；为空时隐藏，避免空行占用高度。 */
    public void setTask(String value) {
        String text = value == null ? "" : value.trim();
        taskLabel.setText(text);
        taskLabel.setVisible(!text.isEmpty());
        taskLabel.setManaged(!text.isEmpty());
    }

    /** 打开当前连接的会话历史。 */
    /** 设置「粘贴到终端执行」回调：null 或不设置时指令类回复不提供该按钮以外的行为。 */
    public void setCommandRunner(Consumer<String> runner) {
        this.commandRunner = runner == null ? ignored -> { } : runner;
    }

    public void setHistoryAction(Runnable action) {
        historyAction = action == null ? () -> { } : action;
    }

    /** 挂接“更多”菜单动作。 */
    public void setAgentMenuActions(AgentMenuActions actions) {
        if (actions != null) menuActions = actions;
    }

    /** 同步自动批准勾选状态；运行中禁止切换。 */
    public void setAutoApproveLowRisk(boolean enabled, boolean running) {
        autoApproveItem.setSelected(enabled);
        autoApproveItem.setDisable(running);
    }

    /** 任务运行中禁用“新建/删除/切换”类菜单项；置灰原因显示在“更多”按钮的 Tooltip 上。 */
    public void setTaskMenuEnabled(boolean enabled, String reason) {
        newTaskItem.setDisable(!enabled);
        deleteTaskItem.setDisable(!enabled);
        autoApproveItem.setDisable(!enabled);
        if (enabled) {
            moreMenu.setTooltip(null);
        } else {
            String text = reason == null || reason.isBlank() ? I18n.tr("agent.menu.disabledRunning") : reason;
            moreMenu.setTooltip(new Tooltip(text));
        }
    }

    /** 提供命令参数候选；输入“/resume ”等加空格的命令时调用。 */
    public void setArgumentSuggestions(Function<String, List<ArgumentSuggestion>> provider) {
        argumentSuggestionProvider = provider == null ? ignored -> List.of() : provider;
    }

    /** 聚焦 AI 模型切换入口（错误卡片“切换模型”使用）。 */
    public void focusAiSelector() {
        aiSelector.requestFocus();
    }

    /** 填充可切换的 AI 模型列表；selectedId 是当前配置名，选中项不变时不触发回调。 */
    public void setAiProfiles(List<AiProfile> profiles, String selectedId, Consumer<String> handler) {
        if (handler != null) aiSelectionHandler = handler;
        List<AiProfile> safeProfiles = profiles == null ? List.of() : List.copyOf(profiles);
        updatingAiProfiles = true;
        try {
            aiSelector.setVisible(!safeProfiles.isEmpty());
            aiSelector.setManaged(!safeProfiles.isEmpty());
            if (!aiSelector.getItems().equals(safeProfiles)) aiSelector.getItems().setAll(safeProfiles);
            AiProfile selected = safeProfiles.stream()
                    .filter(profile -> profile.id() != null && profile.id().equals(selectedId))
                    .findFirst().orElse(null);
            if (selected == null) {
                aiSelector.getSelectionModel().clearSelection();
            } else if (!selected.equals(aiSelector.getValue())) {
                aiSelector.setValue(selected);
            }
        } finally {
            updatingAiProfiles = false;
        }
    }

    /** 任务运行中禁止切换，避免当前步骤与下一轮使用不同配置。 */
    public void setAiSwitchingEnabled(boolean enabled) {
        aiSelector.setDisable(!enabled);
    }
    public void prepareGoal(String value) { input.setText(value); input.requestFocus(); input.positionCaret(input.getLength()); }
    public void requestInputFocus() { input.requestFocus(); }
}
