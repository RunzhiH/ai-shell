/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/** Stable Claude-style Agent UI: scrollable transcript with a layout-anchored prompt. */
public final class AgentConsoleView extends BorderPane {
    public record Command(String name, String description) { }
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final Pattern TRUE_COLOR = Pattern.compile("\\u001B\\[38;2;(\\d+);(\\d+);(\\d+)m");
    private static final String MONO = "-fx-font-family: 'Cascadia Code', 'Consolas', monospace;";
    private final VBox transcript = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(transcript);
    private final TextArea input = new TextArea();
    private final ListView<String> suggestions = new ListView<>();
    private final Label status = new Label("未选择连接 · 输入 / 查看命令");
    private final List<Command> commands;
    private final List<String> inputHistory = new ArrayList<>();
    private final Consumer<String> lineHandler;
    private final Runnable interruptHandler;
    private final Runnable clearHandler;
    private int historyOffset;

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
        input.setPromptText("输入目标或 / 查看命令");
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

        status.setStyle(MONO + " -fx-text-fill: -fx-text-secondary; -fx-font-size: 11px;");
        VBox bottom = new VBox(5, suggestions, inputRow, status);
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
        print("\u001b[38;2;240;246;252m❯ " + value + "\u001b[0m");
        lineHandler.accept(value);
    }

    private boolean isCompleteCommand(String value) {
        return commands.stream().anyMatch(command -> command.name().equalsIgnoreCase(value));
    }

    private void updateSuggestions(String value) {
        String typed = value == null ? "" : value.stripLeading().toLowerCase(Locale.ROOT);
        if (!typed.startsWith("/") || typed.contains(" ") || typed.contains("\n")) { showSuggestions(false); return; }
        List<String> matches = commands.stream().filter(command -> command.name().startsWith(typed))
                .map(command -> String.format("%-14s %s", command.name(), command.description())).toList();
        suggestions.getItems().setAll(matches);
        showSuggestions(!matches.isEmpty());
        if (!matches.isEmpty()) suggestions.getSelectionModel().selectFirst();
    }

    private void acceptSuggestion() {
        String selected = suggestions.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        input.setText(selected.stripLeading().split("\\s+", 2)[0] + " ");
        input.positionCaret(input.getLength());
        showSuggestions(false);
        input.requestFocus();
    }

    private void showSuggestions(boolean visible) {
        suggestions.setVisible(visible);
        suggestions.setManaged(visible);
        if (visible) suggestions.setPrefHeight(Math.min(216, 8 + suggestions.getItems().size() * 25));
    }

    public void welcome() {
        transcript.getChildren().clear();
        Label title = new Label("AI Shell Agent");
        title.setStyle(MONO + " -fx-text-fill: -fx-ai-color; -fx-font-size: 16px; -fx-font-weight: bold;");
        Label hint = new Label("SSH Agent workspace  ·  输入 / 查看命令");
        hint.setStyle(MONO + " -fx-text-fill: -fx-text-secondary;");
        transcript.getChildren().addAll(title, hint);
        input.requestFocus();
    }

    public void print(String ansiText) {
        Matcher matcher = TRUE_COLOR.matcher(ansiText == null ? "" : ansiText);
        String color = "-fx-text-primary";
        if (matcher.find()) color = String.format("#%02x%02x%02x", Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        // 标准前景色跟随主题，避免浅色背景上的浅色输出。
        color = switch (color.toLowerCase(Locale.ROOT)) {
            case "#f0f6fc", "#c9d1d9", "#e6edf3" -> "-fx-text-primary";
            case "#8b949e", "#6e7681" -> "-fx-text-secondary";
            case "#56d364", "#3fb950" -> "-fx-success-color";
            case "#d8b4fe", "#a371f7" -> "-fx-ai-color";
            case "#f85149" -> "-fx-danger-color";
            case "#d29922" -> "-fx-warning-color";
            default -> color;
        };
        String plain = ANSI.matcher(ansiText == null ? "" : ansiText).replaceAll("");
        TextArea block = new TextArea(plain);
        block.setEditable(false);
        block.setWrapText(true);
        int lines = Math.max(1, plain.split("\\R", -1).length + plain.length() / 55);
        block.setPrefHeight(Math.min(260, 7 + lines * 17));
        block.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        block.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        block.setStyle(MONO + " -fx-control-inner-background: -fx-code-bg; -fx-background-color: -fx-code-bg;"
                + " -fx-text-fill: " + color + "; -fx-border-color: transparent; -fx-background-insets: 0;"
                + " -fx-padding: 1 0; -fx-highlight-fill: -fx-primary-color; -fx-highlight-text-fill: white;");
        transcript.getChildren().add(block);
        javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    /** Renders a proposed shell command with copy and explicit approval actions. */
    public void printCommandProposal(String command, String risk, Consumer<Boolean> decision,
                                    Consumer<String> commandInserter) {
        Label heading = new Label("命令提议 · " + risk);
        heading.getStyleClass().addAll("ai-command-title", "ai-command-risk");
        Label code = new Label(command);
        code.setWrapText(true);
        code.getStyleClass().add("ai-command-text");
        Button copy = new Button("复制");
        copy.getStyleClass().add("ai-command-btn");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(command);
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("命令已复制");
        });
        Button send = new Button("发送到终端");
        send.getStyleClass().add("ai-command-btn");
        send.setOnAction(event -> {
            commandInserter.accept(command);
            setStatus("命令已填入 Terminal，请检查后回车执行");
        });
        Button approve = new Button("执行");
        approve.getStyleClass().add("ai-command-btn");
        Button reject = new Button("拒绝");
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
                        "该命令可能造成不可恢复的系统或数据变更。\n\n" + command,
                        ButtonType.CANCEL, ButtonType.OK);
                alert.setTitle("高风险命令二次确认");
                alert.setHeaderText("确认执行高风险命令？");
                confirmed = alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
            }
            decideOnce.accept(confirmed);
        });
        reject.setOnAction(event -> decideOnce.accept(false));
        HBox actions = new HBox(6, copy, send, approve, reject);
        VBox card = new VBox(7, heading, code, actions);
        card.getStyleClass().add("ai-command-card");
        transcript.getChildren().add(card);
        javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    public void clear() { welcome(); }
    public void setStatus(String value) { status.setText(value == null ? "" : value); }
    public void requestInputFocus() { input.requestFocus(); }
}
