/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ui;

import com.runzhi.aishell.agent.AgentHistoryStore;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 会话历史面板：可搜索、可恢复、可重命名、可删除、可清空、可导出。
 *
 * <p>展示层数据由 {@link HistoryItem} 派生，不新增持久化字段；
 * 所有动作通过 {@link HistoryActions} 回调交还控制器执行。</p>
 */
public final class AgentHistoryDialog {
    /** 列表项：任务元数据 + 展示层派生字段。 */
    public record HistoryItem(String connectionKey, String taskId, String title, int ordinal,
                              long updatedAt, int messageCount, String preview,
                              String searchable, boolean current) { }

    /** 面板动作回调；实现方负责真正修改历史与工作区状态。 */
    public interface HistoryActions {
        void resume(String taskId);
        void rename(String taskId, String title);
        void delete(String taskId);
        void clearAll();
        void export(Path file);
    }

    private static final int PREVIEW_LIMIT = 120;
    private static final int SEARCHABLE_LIMIT = 4000;

    private final Stage stage;
    private final ListView<HistoryItem> list = new ListView<>();
    private final List<HistoryItem> items;
    private final FilteredList<HistoryItem> filtered;
    private final HistoryActions actions;
    private final TextField search = new TextField();
    private final Label count = new Label();

    public AgentHistoryDialog(Window owner, List<HistoryItem> items, HistoryActions actions) {
        this.items = new ArrayList<>(items == null ? List.of() : items);
        this.filtered = new FilteredList<>(FXCollections.observableArrayList(this.items), item -> true);
        this.actions = actions == null ? noOpActions() : actions;
        this.stage = new Stage();
        build(owner);
    }

    private void build(Window owner) {
        stage.initOwner(owner);
        stage.setTitle(I18n.tr("agent.history.panel.title"));
        stage.setMinWidth(480);
        stage.setWidth(620);
        stage.setHeight(480);

        search.setPromptText(I18n.tr("agent.history.search.prompt"));
        search.getStyleClass().add("agent-history-search");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((ignored, previous, value) -> applyFilter(value));


        Button exportButton = new Button(I18n.tr("agent.history.export"));
        exportButton.getStyleClass().add("ai-command-btn");
        exportButton.setOnAction(event -> export());
        Button clearButton = new Button(I18n.tr("agent.history.clear"));
        clearButton.getStyleClass().add("ai-command-btn");
        clearButton.setOnAction(event -> clearAll());
        Button closeButton = new Button(I18n.tr("agent.history.close"));
        closeButton.getStyleClass().add("ai-command-btn");
        closeButton.setOnAction(event -> stage.close());
        HBox toolbar = new HBox(8, search, exportButton, clearButton, closeButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(10));

        list.setPlaceholder(new Label(I18n.tr("agent.history.empty")));
        list.setItems(filtered);
        list.setCellFactory(view -> new HistoryCell());
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() >= 2) resumeSelected();
        });
        HBox footer = new HBox(8, count);
        footer.setPadding(new Insets(6, 10, 8, 10));
        count.setStyle("-fx-text-fill: -fx-text-secondary; -fx-font-size: 11px;");

        VBox root = new VBox(toolbar, list, footer);
        root.setStyle("-fx-background-color: -fx-panel-bg;");
        stage.setScene(new javafx.scene.Scene(root));
        stage.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { event.consume(); stage.close(); }
            else if (event.isControlDown() && event.getCode() == KeyCode.F) { event.consume(); search.requestFocus(); }
            else if (event.getCode() == KeyCode.ENTER && list.isFocused()) { event.consume(); resumeSelected(); }
            else if (event.getCode() == KeyCode.DELETE && list.isFocused()) { event.consume(); deleteSelected(); }
        });
        updateCount();
    }

    public void show() {
        if (!stage.isShowing()) stage.show();
        search.requestFocus();
    }

    public void close() {
        stage.close();
    }

    /** 按关键词过滤标题、预览与消息内容（调用方须在 FX 线程）。 */
    public void applyFilter(String query) {
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            filtered.setPredicate(item -> true);
        } else {
            filtered.setPredicate(item -> (item.title() + " " + item.preview() + " " + item.searchable())
                    .toLowerCase(Locale.ROOT).contains(keyword));
        }
        updateCount();
    }

    public List<HistoryItem> visibleItems() {
        return new ArrayList<>(filtered);
    }

    private void updateCount() {
        count.setText(I18n.tr("agent.history.count", filtered.size(), items.size()));
    }

    private void resumeSelected() {
        HistoryItem selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        actions.resume(selected.taskId());
        stage.close();
    }

    private void deleteSelected() {
        HistoryItem selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle(I18n.tr("agent.history.delete.title"));
        confirm.setHeaderText(I18n.tr("agent.history.delete.header", selected.title()));
        confirm.setContentText(I18n.tr("agent.history.delete.content"));
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            actions.delete(selected.taskId());
        }
    }

    private void renameSelected(HistoryItem item) {
        TextInputDialog dialog = new TextInputDialog(item.title());
        dialog.initOwner(stage);
        dialog.setTitle(I18n.tr("agent.history.rename.title"));
        dialog.setHeaderText(I18n.tr("agent.history.rename.header", item.title()));
        dialog.setContentText(I18n.tr("agent.history.rename.content"));
        dialog.showAndWait().ifPresent(title -> {
            String trimmed = title.trim();
            if (trimmed.isEmpty() || trimmed.equals(item.title())) return;
            actions.rename(item.taskId(), trimmed);
        });
    }

    private void clearAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle(I18n.tr("agent.history.clear.title"));
        confirm.setHeaderText(I18n.tr("agent.history.clear.header"));
        confirm.setContentText(I18n.tr("agent.history.delete.content"));
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            actions.clearAll();
        }
    }

    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.tr("agent.history.export"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        chooser.setInitialFileName("agent-history.md");
        java.io.File chosen = chooser.showSaveDialog(stage);
        if (chosen != null) actions.export(chosen.toPath());
    }

    /** 从持久化记录构建展示项；preview 取最后一条有意义的文本。 */
    public static HistoryItem from(AgentHistoryStore.TaskRecord task, String connectionKey, boolean current) {
        List<AgentHistoryStore.EventRecord> events = task.events() == null ? List.of() : task.events();
        String title = task.title() == null || task.title().isBlank()
                ? I18n.tr("agent.workspace.taskNumber", Math.max(1, task.ordinal())) : task.title();
        String preview = "";
        for (int index = events.size() - 1; index >= 0; index--) {
            AgentHistoryStore.EventRecord event = events.get(index);
            String kind = event.kind() == null ? "" : event.kind();
            if ("USER".equals(kind) || "RESPONSE".equals(kind) || "ERROR".equals(kind) || "SUCCESS".equals(kind)) {
                String text = event.text() == null ? "" : event.text().trim().replaceAll("[\\s]+", " ");
                if (!text.isEmpty()) { preview = text; break; }
            }
        }
        if (preview.length() > PREVIEW_LIMIT) preview = preview.substring(0, PREVIEW_LIMIT) + "…";
        StringBuilder searchable = new StringBuilder();
        for (AgentHistoryStore.EventRecord event : events) {
            if (event.text() != null) searchable.append(event.text()).append('\n');
            if (searchable.length() >= SEARCHABLE_LIMIT) break;
        }
        return new HistoryItem(connectionKey, task.id(), title, Math.max(1, task.ordinal()),
                task.updatedAt(), events.size(), preview, searchable.toString(), current);
    }

    /** “2 分钟前 / 昨天 / 日期”式相对时间。 */
    public static String relativeTime(long epochMillis) {
        long seconds = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now()).getSeconds();
        if (seconds < 60) return I18n.tr("agent.history.time.justNow");
        long minutes = seconds / 60;
        if (minutes < 60) return I18n.tr("agent.history.time.minutesAgo", minutes);
        long hours = minutes / 60;
        if (hours < 24) return I18n.tr("agent.history.time.hoursAgo", hours);
        long days = hours / 24;
        if (days == 1) return I18n.tr("agent.history.time.yesterday");
        if (days < 30) return I18n.tr("agent.history.time.daysAgo", days);
        return java.time.LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault())
                .toString();
    }

    private static HistoryActions noOpActions() {
        return new HistoryActions() {
            @Override public void resume(String taskId) { }
            @Override public void rename(String taskId, String title) { }
            @Override public void delete(String taskId) { }
            @Override public void clearAll() { }
            @Override public void export(Path file) { }
        };
    }

    /** 列表单元格：当前任务标记、标题、摘要、相对时间与行内操作按钮。 */
    private final class HistoryCell extends javafx.scene.control.ListCell<HistoryItem> {
        @Override
        protected void updateItem(HistoryItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); setText(null); return; }
            Label marker = new Label(item.current() ? "●" : "");
            marker.setStyle("-fx-text-fill: -fx-primary-color;");
            marker.setMinWidth(14);
            Label title = new Label(item.title());
            title.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");
            Label time = new Label(relativeTime(item.updatedAt()));
            time.setStyle("-fx-text-fill: -fx-text-secondary; -fx-font-size: 11px;");
            Label summary = new Label(I18n.tr("agent.history.summary", item.messageCount(), item.preview()));
            summary.setWrapText(true);
            summary.setMaxWidth(Double.MAX_VALUE);
            summary.setStyle("-fx-text-fill: -fx-text-secondary; -fx-font-size: 12px;");
            HBox header = new HBox(6, marker, title, time);
            HBox.setHgrow(title, Priority.ALWAYS);
            Button resume = smallButton(I18n.tr("agent.history.resume"));
            resume.setOnAction(event -> { actions.resume(item.taskId()); stage.close(); });
            Button rename = smallButton(I18n.tr("agent.history.rename"));
            rename.setOnAction(event -> renameSelected(item));
            Button delete = smallButton(I18n.tr("agent.history.delete"));
            delete.setOnAction(event -> deleteSelected());
            HBox actionsRow = new HBox(6, resume, rename, delete);
            VBox box = new VBox(3, header, summary, actionsRow);
            box.setPadding(new Insets(8));
            box.setStyle("-fx-border-color: -fx-border-default; -fx-border-radius: 6;"
                    + " -fx-background-color: -fx-panel-bg;");
            setGraphic(box);
        }

        private Button smallButton(String text) {
            Button button = new Button(text);
            button.getStyleClass().add("ai-command-btn");
            return button;
        }
    }
}
