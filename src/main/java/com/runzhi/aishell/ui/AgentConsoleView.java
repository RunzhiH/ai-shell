/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ui;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ClipboardUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

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
    private static final String WELCOME_MARKER = "agentWelcome";
    private final VBox transcript = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(transcript);
    private final TextArea input = new TextArea();
    private final ListView<String> suggestions = new ListView<>();
    private final Label status = new Label(I18n.tr("agent.idle"));
    private final Label taskLabel = new Label();
    private final Button historyButton = new Button(I18n.tr("agent.history.button"));
    private Runnable historyAction = () -> { };
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
        boolean follow = isNearBottom();
        double followValue = scroll.getVvalue();
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
        TextFlow body = textFlow(normalized, false, Role.ERROR);
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
        scrollIfFollow(follow, followValue);
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
        double followValue = scroll.getVvalue();
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
            body.getChildren().add(textFlow(text, false, role));
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
            body.getChildren().add(textFlow(text, false, role));
        }
        final String copyValue = copyText;
        copy.setOnAction(event -> copyMessage(copyValue));
        block.getChildren().addAll(header, body);
        transcript.getChildren().add(block);
        scrollIfFollow(follow, followValue);
    }

    private TextFlow textFlow(String text, boolean code, Role role) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        flow.setMinWidth(0);
        flow.setMaxWidth(Double.MAX_VALUE);
        Text node = new Text(text);
        node.getStyleClass().add(code ? "agent-message-code" : "agent-message-text");
        node.getStyleClass().add("agent-message-text-" + role.name().toLowerCase(Locale.ROOT));
        flow.getChildren().add(node);
        return flow;
    }

    private void appendTechnicalBody(VBox body, String text, Role role) {
        TextFlow flow = textFlow(text, true, role);
        if (text.length() > 240 || text.lines().count() > 4) {
            TitledPane details = new TitledPane(I18n.tr("agent.message.details"), flow);
            details.setAnimated(false);
            details.setExpanded(false);
            details.setMaxWidth(Double.MAX_VALUE);
            details.getStyleClass().add("agent-message-details");
            body.getChildren().add(details);
        } else {
            body.getChildren().add(flow);
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
        return content - viewport - offset <= 36;
    }

    private void scrollIfFollow(boolean follow, double capturedValue) {
        if (!follow) return;
        javafx.application.Platform.runLater(() -> {
            // A queued scroll must not override a user who scrolled up after the append.
            if (scroll.getVvalue() + 0.0005 >= capturedValue) scroll.setVvalue(1.0);
        });
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
        reject.getStyleClass().add("ai-command-btn-danger");
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
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(6, 6, copy, send, approve, reject);
        VBox card = new VBox(7, heading, code, actions);
        if(proposal!=null) {
            var context=proposal.context();
            Label metadata=new Label(I18n.tr("agent.approval.context", context.task(), proposal.id(),
                    context.user(),context.host(),proposal.sessionId(),context.directory(),context.impact()));
            metadata.setWrapText(true); metadata.setMinWidth(0);
            Label state=new Label(); state.setWrapText(true); state.setMinWidth(0);
            Label safety=new Label(I18n.tr("agent.highWarning")); safety.setWrapText(true); safety.setMinWidth(0);
            Button restartButton=new Button(); restartButton.getStyleClass().add("ai-command-btn");
            restartButton.setOnAction(e->restart.run()); actions.getChildren().add(restartButton);
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
        boolean follow = isNearBottom();
        double followValue = scroll.getVvalue();
        transcript.getChildren().add(card);
        updateApprovalNavigation();
        scrollIfFollow(follow, followValue);
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
