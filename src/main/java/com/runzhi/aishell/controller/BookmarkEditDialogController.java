/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.features.bookmark.BookmarkManager;
import com.runzhi.aishell.features.bookmark.CommandBookmark;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * 书签编辑对话框控制器
 */
@Slf4j
public class BookmarkEditDialogController extends BaseController implements Initializable {
    
    @FXML
    private Label dialogTitleLabel;
    
    @FXML
    private TextField nameField;
    
    @FXML
    private TextArea commandArea;
    
    @FXML
    private TextField descriptionField;
    
    @FXML
    private ComboBox<String> categoryComboBox;
    
    @FXML
    private TextField tagsField;
    
    @FXML
    private CheckBox enabledCheckBox;
    
    // 当前编辑的书签（如果为 null 则是新建模式）
    private CommandBookmark editingBookmark;
    
    // 回调函数
    private Runnable onSaveCallback;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        super.initialize(location, resources);
        
        // 初始化分类下拉框
        initializeCategoryComboBox();
    }
    
    /**
     * 初始化分类下拉框
     */
    private void initializeCategoryComboBox() {
        Set<String> categories = BookmarkManager.getInstance().getCategories();
        categoryComboBox.getItems().addAll(categories);
        
        // 添加常用分类建议
        if (!categoryComboBox.getItems().contains("系统监控")) {
            categoryComboBox.getItems().add("系统监控");
        }
        if (!categoryComboBox.getItems().contains("文件操作")) {
            categoryComboBox.getItems().add("文件操作");
        }
        if (!categoryComboBox.getItems().contains("网络工具")) {
            categoryComboBox.getItems().add("网络工具");
        }
        if (!categoryComboBox.getItems().contains("系统维护")) {
            categoryComboBox.getItems().add("系统维护");
        }
        if (!categoryComboBox.getItems().contains("部署发布")) {
            categoryComboBox.getItems().add("部署发布");
        }
        if (!categoryComboBox.getItems().contains("数据库")) {
            categoryComboBox.getItems().add("数据库");
        }
    }
    
    /**
     * 设置编辑模式
     */
    public void setEditMode(CommandBookmark bookmark) {
        this.editingBookmark = bookmark;
        dialogTitleLabel.setText("编辑书签");
        
        // 填充数据
        nameField.setText(bookmark.getName());
        commandArea.setText(bookmark.getCommand());
        descriptionField.setText(bookmark.getDescription());
        categoryComboBox.setValue(bookmark.getCategory());
        tagsField.setText(bookmark.getTags());
        enabledCheckBox.setSelected(bookmark.isEnabled());
    }
    
    /**
     * 设置添加模式
     */
    public void setAddMode() {
        this.editingBookmark = null;
        dialogTitleLabel.setText("添加书签");
        
        // 清空表单
        nameField.clear();
        commandArea.clear();
        descriptionField.clear();
        categoryComboBox.setValue(null);
        tagsField.clear();
        enabledCheckBox.setSelected(true);
    }
    
    /**
     * 设置保存回调
     */
    public void setOnSaveCallback(Runnable callback) {
        this.onSaveCallback = callback;
    }
    
    @FXML
    private void handleSave() {
        // 验证必填字段
        String name = nameField.getText().trim();
        String command = commandArea.getText().trim();
        
        if (name.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("验证失败");
            alert.setHeaderText(null);
            alert.setContentText("请输入书签名称");
            alert.showAndWait();
            nameField.requestFocus();
            return;
        }
        
        if (command.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("验证失败");
            alert.setHeaderText(null);
            alert.setContentText("请输入命令内容");
            alert.showAndWait();
            commandArea.requestFocus();
            return;
        }
        
        String category = categoryComboBox.getValue() != null ? 
            categoryComboBox.getValue().trim() : "未分类";
        
        if (editingBookmark == null) {
            // 新建模式
            CommandBookmark bookmark = new CommandBookmark(name, command, category);
            bookmark.setDescription(descriptionField.getText().trim());
            bookmark.setTags(tagsField.getText().trim());
            bookmark.setEnabled(enabledCheckBox.isSelected());
            
            BookmarkManager.getInstance().addBookmark(bookmark);
            log.info("新建书签：{}", name);
        } else {
            // 编辑模式
            editingBookmark.setName(name);
            editingBookmark.setCommand(command);
            editingBookmark.setCategory(category);
            editingBookmark.setDescription(descriptionField.getText().trim());
            editingBookmark.setTags(tagsField.getText().trim());
            editingBookmark.setEnabled(enabledCheckBox.isSelected());
            
            BookmarkManager.getInstance().updateBookmark(editingBookmark);
            log.info("更新书签：{}", name);
        }
        
        // 调用回调
        if (onSaveCallback != null) {
            onSaveCallback.run();
        }
        
        // 关闭对话框
        closeDialog();
    }
    
    @FXML
    private void handleCancel() {
        closeDialog();
    }
    
    /**
     * 关闭对话框
     */
    private void closeDialog() {
        Stage stage = (Stage) nameField.getScene().getWindow();
        stage.close();
    }
}
