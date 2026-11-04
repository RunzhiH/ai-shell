/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import com.runzhi.aishell.common.i18n.I18n;
import java.util.LinkedHashMap;
import com.runzhi.aishell.common.config.ShortcutConfigStore;
import javafx.scene.input.KeyCombination;
import javafx.stage.FileChooser;

/**
 * 快捷键设置控制器
 */
@Slf4j
public class SettingsShortcutsController {
    
    @FXML private TextField searchField;
    @FXML private TableView<ShortcutInfo> shortcutsTable;
    @FXML private TableColumn<ShortcutInfo, String> colCategory;
    @FXML private TableColumn<ShortcutInfo, String> colFunction;
    @FXML private TableColumn<ShortcutInfo, String> colShortcut;
    @FXML private TableColumn<ShortcutInfo, Boolean> colConflict;
    @FXML private ComboBox<String> presetCombo;
    @FXML private Label statusLabel;
    
    // 快捷键数据列表
    private ObservableList<ShortcutInfo> shortcutList = FXCollections.observableArrayList();
    private final Map<String, String> defaults = com.runzhi.aishell.common.config.ShortcutConfigStore.defaults();
    
    /** Unsaved values belong to this settings dialog only. */
    private final Map<String, String> draft = new LinkedHashMap<>(ShortcutConfigStore.snapshot());

    /**
     * 初始化方法
     */
    @FXML
    public void initialize() {
        log.info("初始化快捷键设置页面");
        
        // 初始化表格
        setupTable();
        presetCombo.getItems().setAll(
                I18n.tr("shortcuts.preset.windows"), I18n.tr("shortcuts.preset.mac"),
                I18n.tr("shortcuts.preset.linux"), I18n.tr("shortcuts.preset.vscode"),
                I18n.tr("shortcuts.preset.idea"));
        
        // 加载默认快捷键
        loadDefaultShortcuts();
        
        // 填充表格数据
        shortcutsTable.setItems(shortcutList);
        
        // 监听搜索
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filterShortcuts(newVal);
        });
    }
    
    /**
     * 设置表格
     */
    private void setupTable() {
        colCategory.setCellValueFactory(new PropertyValueFactory<>("category"));
        colFunction.setCellValueFactory(new PropertyValueFactory<>("functionLabel"));
        colShortcut.setCellValueFactory(new PropertyValueFactory<>("shortcut"));
        colConflict.setCellValueFactory(new PropertyValueFactory<>("conflict"));
        
        // 冲突列使用红色标记
        colConflict.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item ? "⚠ " + I18n.tr("shortcuts.conflictMark") : "");
                    setStyle(item ? "-fx-text-fill: -fx-warning-color; -fx-font-weight: bold;" : "");
                }
            }
        });
    }
    
    /**
     * 加载默认快捷键
     */
    private void loadDefaultShortcuts() {
        shortcutList.clear();
        
        defaults.forEach((action, ignored) -> shortcutList.add(new ShortcutInfo(
                action.contains("连接") ? "连接" : action.contains("设置") ? "系统" : "终端",
                action, draft.get(action), false)));
        
        checkConflicts();
    }
    
    /**
     * 检查快捷键冲突
     */
    private void checkConflicts() {
        // 重置所有冲突标记
        for (ShortcutInfo shortcut : shortcutList) {
            shortcut.setConflict(false);
        }
        
        // 检查重复的快捷键
        for (int i = 0; i < shortcutList.size(); i++) {
            for (int j = i + 1; j < shortcutList.size(); j++) {
                ShortcutInfo s1 = shortcutList.get(i);
                ShortcutInfo s2 = shortcutList.get(j);
                
                if (!s1.getShortcut().isBlank() && !s2.getShortcut().isBlank()
                        && KeyCombination.valueOf(s1.getShortcut().replace("Comma", ",")).equals(
                                KeyCombination.valueOf(s2.getShortcut().replace("Comma", ",")))) {
                    s1.setConflict(true);
                    s2.setConflict(true);
                }
            }
        }
        
        shortcutsTable.refresh();
    }
    
    /**
     * 搜索快捷键
     */
    @FXML
    private void handleSearch() {
        filterShortcuts(searchField.getText());
    }
    
    /**
     * 过滤快捷键
     */
    private void filterShortcuts(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            shortcutsTable.setItems(shortcutList);
        } else {
            ObservableList<ShortcutInfo> filtered = FXCollections.observableArrayList();
            for (ShortcutInfo shortcut : shortcutList) {
                if (shortcut.getFunctionLabel().toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT)) ||
                    shortcut.getShortcut().toLowerCase().contains(keyword.toLowerCase())) {
                    filtered.add(shortcut);
                }
            }
            shortcutsTable.setItems(filtered);
        }
    }
    
    /**
     * 编辑快捷键
     */
    @FXML
    private void handleEditShortcut() {
        ShortcutInfo selected = shortcutsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(I18n.tr("shortcuts.notice"), I18n.tr("shortcuts.selectEdit"));
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog(selected.getShortcut());
        dialog.setTitle(I18n.tr("shortcuts.editTitle"));
        dialog.setHeaderText(selected.getFunctionLabel());
        dialog.setContentText(I18n.tr("shortcuts.keyPrompt"));
        dialog.showAndWait().ifPresent(value -> {
            try {
                if (!value.isBlank()) KeyCombination.valueOf(value.replace("Comma", ","));
                selected.setShortcut(value.trim());
                draft.put(selected.getFunction(), value.trim());
                checkConflicts();
                statusLabel.setText(I18n.tr("shortcuts.pending"));
            } catch (IllegalArgumentException e) { showAlert(I18n.tr("shortcuts.invalid"), value); }
        });
    }
    
    /**
     * 清除快捷键
     */
    @FXML
    private void handleClearShortcut() {
        ShortcutInfo selected = shortcutsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(I18n.tr("shortcuts.notice"), I18n.tr("shortcuts.selectClear"));
            return;
        }
        
        selected.setShortcut("");
        draft.put(selected.getFunction(), "");
        checkConflicts();
        shortcutsTable.refresh();
        statusLabel.setText(I18n.tr("shortcuts.cleared"));
    }
    
    /**
     * 恢复默认
     */
    @FXML
    private void handleRestoreDefault() {
        ShortcutInfo selected = shortcutsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(I18n.tr("shortcuts.notice"), I18n.tr("shortcuts.selectRestore"));
            return;
        }
        
        String value = defaults.get(selected.getFunction());
        selected.setShortcut(value);
        draft.put(selected.getFunction(), value);
        checkConflicts();
        statusLabel.setText(I18n.tr("shortcuts.restored"));
    }
    
    /**
     * 重置所有
     */
    @FXML
    private void handleResetAll() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.tr("shortcuts.confirmReset"));
        alert.setHeaderText(null);
        alert.setContentText(I18n.tr("shortcuts.resetQuestion"));
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                draft.clear();
                draft.putAll(defaults);
                loadDefaultShortcuts();
                statusLabel.setText(I18n.tr("shortcuts.reset"));
            }
        });
    }
    
    /**
     * 应用预设
     */
    @FXML
    private void handleApplyPreset() {
        String preset = presetCombo.getValue();
        if (preset == null) {
            showAlert(I18n.tr("shortcuts.notice"), I18n.tr("shortcuts.selectPresetFirst"));
            return;
        }
        
        boolean mac = preset.startsWith("macOS");
        for (ShortcutInfo item : shortcutList) {
            String value = defaults.get(item.getFunction());
            if (mac) value = value.replace("Ctrl+", "Meta+");
            item.setShortcut(value);
            draft.put(item.getFunction(), value);
        }
        checkConflicts();
        statusLabel.setText(I18n.tr("shortcuts.presetApplied", preset));
    }
    
    /**
     * 导出快捷键
     */
    @FXML
    private void handleExportShortcuts() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.tr("shortcuts.export"));
        chooser.setInitialFileName("aishell-shortcuts.json");
        java.io.File file = chooser.showSaveDialog(shortcutsTable.getScene().getWindow());
        if (file == null) return;
        try { com.runzhi.aishell.common.config.ShortcutConfigStore.exportTo(file.toPath(), draft); statusLabel.setText(I18n.tr("shortcuts.exported")); }
        catch (Exception e) { showAlert(I18n.tr("shortcuts.exportFailed"), e.getMessage()); }
    }
    
    /**
     * 导入快捷键
     */
    @FXML
    private void handleImportShortcuts() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.tr("shortcuts.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showOpenDialog(shortcutsTable.getScene().getWindow());
        if (file == null) return;
        try { draft.putAll(ShortcutConfigStore.readFrom(file.toPath(), draft)); loadDefaultShortcuts(); statusLabel.setText(I18n.tr("shortcuts.imported")); }
        catch (Exception e) { showAlert(I18n.tr("shortcuts.importFailed"), e.getMessage()); }
    }
    
    /** Validate before the settings dialog writes any page. */
    public void validateSettings() { ShortcutConfigStore.validate(draft); }

    /** Persist only when Apply or Save is requested. */
    public void saveSettings() { ShortcutConfigStore.saveAll(draft); }

    /**
     * 显示提示对话框
     */
    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    /**
     * 快捷键信息类
     */
    public static class ShortcutInfo {
        private final String category;
        private final String function;
        private String shortcut;
        private boolean conflict;
        
        public ShortcutInfo(String category, String function, String shortcut, boolean conflict) {
            this.category = category;
            this.function = function;
            this.shortcut = shortcut;
            this.conflict = conflict;
        }
        
        public String getCategory() {
            return I18n.tr("shortcuts.category." + switch (category) {
                case "连接" -> "connection"; case "系统" -> "system"; default -> "terminal";
            });
        }

        /** Localized label; getFunction remains the stable persisted action identifier. */
        public String getFunctionLabel() {
            String key = switch (function) {
                case "新建终端标签" -> "new"; case "关闭标签" -> "close";
                case "重新连接" -> "reconnect"; case "清屏" -> "clear";
                case "打开设置" -> "settings"; default -> null;
            };
            return key == null ? function : I18n.tr("shortcuts.action." + key);
        }
        
        public String getFunction() {
            return function;
        }
        
        public String getShortcut() {
            return shortcut;
        }
        
        public void setShortcut(String shortcut) {
            this.shortcut = shortcut;
        }
        
        public boolean isConflict() {
            return conflict;
        }
        
        public void setConflict(boolean conflict) {
            this.conflict = conflict;
        }
    }
}