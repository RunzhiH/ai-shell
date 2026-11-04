/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ui;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 点击式命令面板（v6 阶段 2）。
 *
 * <p>仅由顶栏按钮点击打开：<b>不注册任何全局快捷键</b>，Ctrl+K / Ctrl+Shift+K 仍归终端使用。
 * 外观用 field 背景与 border 边界（状态页规范），Esc 关闭，Enter 执行选中项。
 */
public final class CommandPalette extends Popup {

    /** 一条命令：标题、分组、执行动作。 */
    public record Item(String title, String group, Runnable action) { }

    private Node returnFocusAnchor;
    private final List<Item> items;
    private final javafx.scene.control.TextField field = new javafx.scene.control.TextField();
    private final ListView<Item> list = new ListView<>();
    private final VBox content = new VBox();

    public CommandPalette(List<Item> items) {
        this.items = List.copyOf(items);
        setAutoHide(true);
        setHideOnEscape(true);
        setAutoFix(true);
        setOnHidden(event -> {
            Node previous = returnFocusAnchor;
            returnFocusAnchor = null;
            if (previous != null && previous.getScene() != null && previous.getScene().getWindow() != null
                    && previous.getScene().getWindow().isShowing()) previous.requestFocus();
        });
        getContent().add(content);
        build();
    }

    private void build() {
        content.getStyleClass().add("command-palette");
        content.setSpacing(4);
        content.setPadding(new Insets(6));
        content.setPrefWidth(420);
        content.setMaxHeight(360);

        field.setPromptText(com.runzhi.aishell.common.i18n.I18n.tr("main.paletteFilter"));
        field.getStyleClass().add("command-palette-field");
        field.setAccessibleText(I18n.tr("main.paletteFilter"));
        field.setAccessibleHelp(I18n.tr("main.paletteKeys"));
        // 仅面板内部消费按键，不向终端泄漏
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                runSelected();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                hide();
            } else if (event.getCode() == KeyCode.DOWN) {
                event.consume();
                list.getSelectionModel().selectNext();
                list.requestFocus();
            } else if (event.getCode() == KeyCode.UP) {
                event.consume();
                list.getSelectionModel().selectPrevious();
                list.requestFocus();
            }
        });

        list.getStyleClass().add("command-palette-list");
        list.setAccessibleText(I18n.tr("main.paletteResults"));
        Label empty = new Label(I18n.tr("main.paletteEmpty"));
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            {
                setOnMouseClicked(event -> {
                    if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                            && event.getClickCount() == 2 && !isEmpty() && getItem() != null) {
                        list.getSelectionModel().select(getIndex());
                        runSelected();
                        event.consume();
                    }
                });
            }
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setAccessibleText(null);
                    return;
                }
                setText(null);
                setAccessibleText(item.title() + " · " + item.group());
                Label title = new Label(item.title());
                title.getStyleClass().add("command-palette-item-title");
                Label group = new Label(item.group());
                group.getStyleClass().add("command-palette-item-group");
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, title, group);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        list.setItems(FXCollections.observableArrayList(items));
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                runSelected();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                hide();
            }
        });
        if (!items.isEmpty()) list.getSelectionModel().selectFirst();

        content.getChildren().addAll(field, list);
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);

        field.textProperty().addListener((_, _, value) -> applyFilter(value));
    }

    private void applyFilter(String text) {
        String keyword = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Item> visible = new ArrayList<>();
        for (Item item : items) {
            String searchable = (item.title() + " " + item.group()).toLowerCase(Locale.ROOT);
            if (keyword.isEmpty() || java.util.Arrays.stream(keyword.split("\\s+")).allMatch(searchable::contains)) {
                visible.add(item);
            }
        }
        list.setItems(FXCollections.observableArrayList(visible));
        if (!visible.isEmpty()) list.getSelectionModel().selectFirst();
    }

    private void runSelected() {
        Item selected = list.getSelectionModel().getSelectedItem();
        if (selected == null || selected.action() == null) return;
        hide();
        selected.action().run();
    }

    /** Open in screen coordinates, with the visible owner's theme and a fresh query. */
    public void showBelow(Node anchor) {
        if (anchor == null || anchor.getScene() == null) return;
        Window owner = anchor.getScene().getWindow();
        if (owner == null || !owner.isShowing()) return;
        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) return;
        if (isShowing()) hide();
        returnFocusAnchor = anchor;

        List<String> stylesheets = new ArrayList<>(anchor.getScene().getStylesheets());
        stylesheets.addAll(anchor.getScene().getRoot().getStylesheets());
        if (stylesheets.isEmpty()) stylesheets.addAll(List.of(
                com.runzhi.aishell.common.util.ThemeManager.getInstance().getStylesheetsFor(
                        com.runzhi.aishell.common.util.ThemeManager.getInstance().getCurrentTheme())));
        getScene().getStylesheets().setAll(stylesheets.stream().distinct().toList());

        var screens = javafx.stage.Screen.getScreensForRectangle(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
        var screen = (screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        content.setPrefWidth(Math.min(420, screen.getWidth() - 16));
        content.setMaxHeight(Math.min(360, screen.getHeight() - 16));
        field.clear();
        applyFilter("");
        show(owner, bounds.getMinX(), bounds.getMaxY());
        Platform.runLater(() -> { if (isShowing()) field.requestFocus(); });
    }
}
