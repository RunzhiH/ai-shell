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
    
    /**
     * 初始化方法
     */
    @FXML
    public void initialize() {
        log.info("初始化快捷键设置页面");
        
        // 初始化表格
        setupTable();
        
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
        colFunction.setCellValueFactory(new PropertyValueFactory<>("function"));
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
                    setText(item ? "⚠️ 冲突" : "");
                    setStyle(item ? "-fx-text-fill: #F44336; -fx-font-weight: bold;" : "");
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
                action, com.runzhi.aishell.common.config.ShortcutConfigStore.get(action), false)));
        
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
                
                if (s1.getShortcut().equals(s2.getShortcut()) && !s1.getShortcut().isEmpty()) {
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
                if (shortcut.getFunction().toLowerCase().contains(keyword.toLowerCase()) ||
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
            showAlert("提示", "请先选择要编辑的快捷键");
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog(selected.getShortcut());
        dialog.setTitle("编辑快捷键");
        dialog.setHeaderText(selected.getFunction());
        dialog.setContentText("组合键（例如 Ctrl+Shift+L）：");
        dialog.showAndWait().ifPresent(value -> {
            try {
                if (!value.isBlank()) KeyCombination.valueOf(value.replace("Comma", ","));
                selected.setShortcut(value.trim());
                com.runzhi.aishell.common.config.ShortcutConfigStore.set(selected.getFunction(), value.trim());
                checkConflicts();
                statusLabel.setText("快捷键已生效");
            } catch (IllegalArgumentException e) { showAlert("无效快捷键", value); }
        });
    }
    
    /**
     * 清除快捷键
     */
    @FXML
    private void handleClearShortcut() {
        ShortcutInfo selected = shortcutsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("提示", "请先选择要清除的快捷键");
            return;
        }
        
        selected.setShortcut("");
        com.runzhi.aishell.common.config.ShortcutConfigStore.set(selected.getFunction(), "");
        checkConflicts();
        shortcutsTable.refresh();
        statusLabel.setText("已清除快捷键");
    }
    
    /**
     * 恢复默认
     */
    @FXML
    private void handleRestoreDefault() {
        ShortcutInfo selected = shortcutsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("提示", "请先选择要恢复的快捷键");
            return;
        }
        
        String value = defaults.get(selected.getFunction());
        selected.setShortcut(value);
        com.runzhi.aishell.common.config.ShortcutConfigStore.set(selected.getFunction(), value);
        checkConflicts();
        statusLabel.setText("已恢复默认快捷键");
    }
    
    /**
     * 重置所有
     */
    @FXML
    private void handleResetAll() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认重置");
        alert.setHeaderText(null);
        alert.setContentText("确定要将所有快捷键恢复为默认值吗？");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                com.runzhi.aishell.common.config.ShortcutConfigStore.reset();
                loadDefaultShortcuts();
                statusLabel.setText("已重置为默认快捷键");
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
            showAlert("提示", "请先选择预设方案");
            return;
        }
        
        boolean mac = preset.startsWith("macOS");
        for (ShortcutInfo item : shortcutList) {
            String value = defaults.get(item.getFunction());
            if (mac) value = value.replace("Ctrl+", "Meta+");
            item.setShortcut(value);
            com.runzhi.aishell.common.config.ShortcutConfigStore.set(item.getFunction(), value);
        }
        checkConflicts();
        statusLabel.setText("已应用 " + preset);
    }
    
    /**
     * 导出快捷键
     */
    @FXML
    private void handleExportShortcuts() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出快捷键");
        chooser.setInitialFileName("aishell-shortcuts.json");
        java.io.File file = chooser.showSaveDialog(shortcutsTable.getScene().getWindow());
        if (file == null) return;
        try { com.runzhi.aishell.common.config.ShortcutConfigStore.exportTo(file.toPath()); statusLabel.setText("导出完成"); }
        catch (Exception e) { showAlert("导出失败", e.getMessage()); }
    }
    
    /**
     * 导入快捷键
     */
    @FXML
    private void handleImportShortcuts() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入快捷键");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showOpenDialog(shortcutsTable.getScene().getWindow());
        if (file == null) return;
        try { com.runzhi.aishell.common.config.ShortcutConfigStore.importFrom(file.toPath()); loadDefaultShortcuts(); statusLabel.setText("导入完成并已生效"); }
        catch (Exception e) { showAlert("导入失败", e.getMessage()); }
    }
    
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
            return category;
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