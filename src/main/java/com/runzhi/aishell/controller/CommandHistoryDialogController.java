/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.terminal.enhancement.CommandCompletionService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import javafx.stage.FileChooser;

/**
 * 命令历史对话框控制器
 */
@Slf4j
public class CommandHistoryDialogController {
    
    @FXML private TextField searchField;
    @FXML private CheckBox caseSensitiveCheck;
    @FXML private CheckBox regexCheck;
    @FXML private Label totalCountLabel;
    @FXML private TableView<HistoryEntry> historyTable;
    @FXML private TableColumn<HistoryEntry, Integer> colIndex;
    @FXML private TableColumn<HistoryEntry, String> colCommand;
    @FXML private TableColumn<HistoryEntry, String> colTime;
    @FXML private TableColumn<HistoryEntry, String> colStatus;
    
    // 数据
    private ObservableList<HistoryEntry> allEntries = FXCollections.observableArrayList();
    private ObservableList<HistoryEntry> filteredEntries = FXCollections.observableArrayList();
    
    // 服务
    private CommandCompletionService completionService;
    
    // 当前 Tab ID
    private String currentTabId;
    private Consumer<String> commandExecutor;
    
    /**
     * 初始化方法
     */
    @FXML
    public void initialize() {
        log.info("初始化命令历史对话框");
        
        completionService = CommandCompletionService.getInstance();
        
        // 设置表格
        setupTable();
        
        // 加载历史
        loadHistory();
        
        // 监听搜索
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filterHistory();
        });
    }
    
    /**
     * 设置表格
     */
    private void setupTable() {
        colIndex.setCellValueFactory(cellData -> 
            new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().getIndex()).asObject());
        colCommand.setCellValueFactory(cellData -> 
            new javafx.beans.property.SimpleStringProperty(cellData.getValue().getCommand()));
        colTime.setCellValueFactory(cellData -> 
            new javafx.beans.property.SimpleStringProperty(cellData.getValue().getTime()));
        colStatus.setCellValueFactory(cellData -> 
            new javafx.beans.property.SimpleStringProperty(cellData.getValue().getStatus()));
        
        // 状态列使用颜色
        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    if ("成功".equals(item)) {
                        setStyle("-fx-text-fill: #4CAF50;");
                    } else if ("失败".equals(item)) {
                        setStyle("-fx-text-fill: #F44336;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });
    }
    
    /**
     * 加载历史
     */
    private void loadHistory() {
        allEntries.clear();
        
        // 如果 currentTabId 为 null，使用默认值或获取当前活跃 Tab
        String tabId = currentTabId;
        if (tabId == null || tabId.isEmpty()) {
            log.warn("currentTabId 为 null，使用默认值");
            tabId = "default";
        }
        
        List<String> commands = completionService.getHistory(tabId);
        int index = 1;
        
        for (String command : commands) {
            HistoryEntry entry = new HistoryEntry();
            entry.setIndex(index++);
            entry.setCommand(command);
            entry.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            entry.setStatus("成功"); // 简化处理，假设都成功
            
            allEntries.add(entry);
        }
        
        filteredEntries.setAll(allEntries);
        historyTable.setItems(filteredEntries);
        
        updateCount();
    }
    
    /**
     * 过滤历史
     */
    private void filterHistory() {
        String keyword = searchField.getText();
        
        if (keyword == null || keyword.isEmpty()) {
            filteredEntries.setAll(allEntries);
        } else {
            ObservableList<HistoryEntry> filtered = FXCollections.observableArrayList();
            
            boolean caseSensitive = caseSensitiveCheck.isSelected();
            boolean useRegex = regexCheck.isSelected();
            
            for (HistoryEntry entry : allEntries) {
                boolean matches = false;
                
                if (useRegex) {
                    try {
                        String pattern = caseSensitive ? keyword : "(?i)" + keyword;
                        matches = entry.getCommand().matches(pattern);
                    } catch (Exception e) {
                        log.warn("正则表达式无效：{}", keyword);
                        matches = entry.getCommand().contains(keyword);
                    }
                } else {
                    if (caseSensitive) {
                        matches = entry.getCommand().contains(keyword);
                    } else {
                        matches = entry.getCommand().toLowerCase().contains(keyword.toLowerCase());
                    }
                }
                
                if (matches) {
                    filtered.add(entry);
                }
            }
            
            filteredEntries.setAll(filtered);
        }
        
        historyTable.refresh();
        updateCount();
    }
    
    /**
     * 更新计数
     */
    private void updateCount() {
        totalCountLabel.setText(String.valueOf(filteredEntries.size()));
    }
    
    /**
     * 搜索
     */
    @FXML
    private void handleSearch() {
        filterHistory();
    }
    
    /**
     * 清除搜索
     */
    @FXML
    private void handleClearSearch() {
        searchField.clear();
        filterHistory();
    }
    
    /**
     * 执行选中命令
     */
    @FXML
    private void handleExecute() {
        HistoryEntry selected = historyTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("提示", "请先选择要执行的命令");
            return;
        }
        
        if (commandExecutor == null) {
            showAlert("无法执行", "当前历史窗口没有绑定终端。请复制命令后在目标终端中确认执行。");
            return;
        }
        com.runzhi.aishell.agent.CommandRisk assessment =
                com.runzhi.aishell.agent.CommandRiskAssessor.assess(selected.getCommand());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "命令：" + selected.getCommand() + "\n风险：" + assessment + "\n\n确认发送到当前终端？",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("确认执行历史命令");
        confirm.setHeaderText("命令执行需要明确确认");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            commandExecutor.accept(selected.getCommand());
            handleClose();
        }
    }
    
    /**
     * 复制到剪贴板
     */
    @FXML
    private void handleCopy() {
        HistoryEntry selected = historyTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("提示", "请先选择要复制的命令");
            return;
        }
        
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(selected.getCommand());
        clipboard.setContent(content);
        
        log.info("复制命令到剪贴板：{}", selected.getCommand());
        showAlert("已复制", "命令已复制到剪贴板");
    }
    
    /**
     * 删除选中
     */
    @FXML
    private void handleDelete() {
        HistoryEntry selected = historyTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("提示", "请先选择要删除的命令");
            return;
        }
        
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认删除");
        alert.setHeaderText(null);
        alert.setContentText("确定要删除这条历史记录吗？");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                allEntries.remove(selected);
                completionService.removeFromHistory(selected.getCommand(), currentTabId);
                filterHistory();
                log.info("删除历史记录：{}", selected.getCommand());
            }
        });
    }
    
    /**
     * 导出历史
     */
    @FXML
    private void handleExport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出命令历史");
        chooser.setInitialFileName("command-history-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("文本文件", "*.txt"));
        java.io.File file = chooser.showSaveDialog(historyTable.getScene().getWindow());
        if (file == null) return;
        try {
            Files.write(file.toPath(), filteredEntries.stream().map(HistoryEntry::getCommand).toList(),
                    StandardCharsets.UTF_8);
            showAlert("导出完成", "已导出 " + filteredEntries.size() + " 条命令。");
        } catch (Exception e) {
            showAlert("导出失败", e.getMessage());
        }
    }
    
    /**
     * 清空历史
     */
    @FXML
    private void handleClearAll() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认清空");
        alert.setHeaderText(null);
        alert.setContentText("确定要清空所有命令历史吗？\n此操作不可恢复！");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                completionService.clearHistory(currentTabId);
                allEntries.clear();
                filterHistory();
                log.info("清空命令历史");
                showAlert("已清空", "所有命令历史已清空");
            }
        });
    }
    
    /**
     * 关闭对话框
     */
    @FXML
    private void handleClose() {
        historyTable.getScene().getWindow().hide();
    }
    
    /**
     * 设置当前 Tab ID
     * 
     * @param tabId 标签页 ID
     */
    public void setCurrentTabId(String tabId) {
        this.currentTabId = tabId;
        loadHistory();
    }

    public void setCommandExecutor(Consumer<String> commandExecutor) {
        this.commandExecutor = commandExecutor;
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
     * 历史条目数据类
     */
    @Data
    public static class HistoryEntry {
        private int index;
        private String command;
        private String time;
        private String status;
    }
}
