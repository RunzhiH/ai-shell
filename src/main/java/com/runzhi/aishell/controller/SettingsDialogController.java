/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.config.ConfigManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * 设置对话框控制器
 */
@Slf4j
public class SettingsDialogController implements Initializable {

    /** Opens the complete settings center from any application entry point. */
    public static void showDialog(javafx.stage.Window owner) {
        showDialog(owner, null);
    }

    public static void showDialog(javafx.stage.Window owner, String selectCategory) {
        try {
            FXMLLoader loader = new FXMLLoader(SettingsDialogController.class.getResource(
                    "/com/runzhi/aishell/settings-dialog.fxml"));
            loader.setResources(I18n.bundle());
            Parent settingsContent = loader.load();
            SettingsDialogController controller = loader.getController();

            if (selectCategory != null) {
                controller.selectPage(selectCategory);
            }

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle(I18n.tr("settings.title"));
            dialog.setHeaderText(null);
            if (owner != null) dialog.initOwner(owner);
            dialog.getDialogPane().setContent(settingsContent);
            dialog.getDialogPane().setPrefSize(960, 740);
            dialog.setResizable(true);
            com.runzhi.aishell.common.util.UITools.setDialogCSS(dialog.getDialogPane());
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText(I18n.tr("settings.save"));
            ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText(I18n.tr("settings.cancel"));
            dialog.showAndWait().ifPresent(result -> {
                if (result == ButtonType.OK) controller.applySettings();
            });
        } catch (Exception e) {
            log.error("打开设置失败", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.tr("settings.title"));
            alert.setHeaderText(I18n.tr("settings.open.failed"));
            alert.setContentText(e.getMessage());
            if (owner != null) alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    @FXML
    private TreeView<String> settingsTree;
    
    @FXML
    private StackPane contentContainer;
    
    // 模块管理器
    private AIShellModuleManager moduleManager;
    
    // 当前选中的设置页面
    private Parent currentPage;
    
    // 存储设置项与 FXML 路径的映射
    private final java.util.Map<String, String> settingsPageMap = new java.util.HashMap<>();
    // 规范分类 id → 树节点（selectPage 按规范 id 定位，与界面语言无关）
    private final java.util.Map<String, TreeItem<String>> pageById = new java.util.HashMap<>();
    private final java.util.Map<String, Parent> loadedPages = new java.util.HashMap<>();
    private final java.util.Map<String, Object> loadedControllers = new java.util.HashMap<>();
    
    // ConfigManager 实例
    private final ConfigManager configManager = ConfigManager.getInstance();
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        log.info("初始化设置对话框...");
        
        // 初始化设置树
        initializeSettingsTree();
        
        // 默认选中通用设置
        selectDefaultPage();
    }
    
    /**
     * 初始化设置树结构
     */
    private void initializeSettingsTree() {
        // 创建根节点
        TreeItem<String> root = new TreeItem<>(I18n.tr("settings.title"));
        root.setExpanded(true);
        
        // 创建设置分类并注册 FXML 映射
        TreeItem<String> generalCategory = createTreeItem(I18n.tr("settings.general"));
        registerSettingsPage(generalCategory, "/com/runzhi/aishell/settings-general.fxml", "general");
        
        TreeItem<String> sshCategory = createTreeItem(I18n.tr("settings.ssh"));
        registerSettingsPage(sshCategory, "/com/runzhi/aishell/settings-ssh.fxml", "ssh");
        
        TreeItem<String> aiCategory = createTreeItem(I18n.tr("settings.ai"));
        registerSettingsPage(aiCategory, "/com/runzhi/aishell/settings-ai.fxml", "ai");
        
        TreeItem<String> shortcutCategory = createTreeItem(I18n.tr("settings.shortcuts"));
        registerSettingsPage(shortcutCategory, "/com/runzhi/aishell/settings-shortcuts.fxml", "shortcuts");
        
        // 添加到根节点
        root.getChildren().addAll(generalCategory, sshCategory, aiCategory, shortcutCategory);
        
        // 设置到 TreeView
        settingsTree.setRoot(root);
        settingsTree.setShowRoot(false);
        
        // 监听选择变化
        settingsTree.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                loadSettingsPage(newSelection);
            }
        });
        
        log.debug("设置树初始化完成");
    }
    
    /**
     * 创建树节点
     */
    private TreeItem<String> createTreeItem(String text) {
        return new TreeItem<>(text);
    }
    
    /**
     * 注册设置页面映射
     */
    private void registerSettingsPage(TreeItem<String> item, String fxmlPath, String id) {
        settingsPageMap.put(item.getValue(), fxmlPath);
        pageById.put(id, item);
    }
    
    /**
     * 加载设置页面
     */
    private void loadSettingsPage(TreeItem<String> item) {
        String fxmlPath = settingsPageMap.get(item.getValue());
        if (fxmlPath == null) {
            log.debug("设置页面 {} 暂未实现", item.getValue());
            showPlaceholder(item.getValue());
            return;
        }
        
        try {
            Parent cached = loadedPages.get(item.getValue());
            if (cached != null) {
                contentContainer.getChildren().setAll(cached);
                currentPage = cached;
                return;
            }
            log.debug("加载设置页面：{}", fxmlPath);
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            loader.setResources(I18n.bundle());
            Parent page = loader.load();
            
            contentContainer.getChildren().clear();
            contentContainer.getChildren().add(page);
            
            currentPage = page;
            loadedPages.put(item.getValue(), page);
            loadedControllers.put(item.getValue(), loader.getController());
            log.info("设置页面加载成功：{}", fxmlPath);
        } catch (Exception e) {
            log.error("加载设置页面失败：{}", fxmlPath, e);
            showPlaceholder(item.getValue());
        }
    }
    
    /**
     * 显示占位提示
     */
    private void showPlaceholder(String title) {
        VBox placeholder = new VBox(10);
        placeholder.setStyle("-fx-alignment: center; -fx-padding: 20;");
        
        Label label = new Label(I18n.tr("settings.placeholder", title));
        label.getStyleClass().add("text-secondary");
        
        placeholder.getChildren().add(label);
        
        contentContainer.getChildren().clear();
        contentContainer.getChildren().add(placeholder);
    }
    
    /**
     * 按名称选中指定分类
     */
    private void selectPage(String category) {
        selectDefaultPage();
        TreeItem<String> root = settingsTree.getRoot();
        if (root == null) return;
        // 优先按规范 id 定位（与语言无关），再回退到标签包含匹配
        TreeItem<String> byId = pageById.get(category);
        if (byId != null) {
            settingsTree.getSelectionModel().select(byId);
            return;
        }
        for (TreeItem<String> child : root.getChildren()) {
            if (child.getValue() != null && child.getValue().contains(category)) {
                settingsTree.getSelectionModel().select(child);
                return;
            }
        }
    }

    /**
     * 默认选中通用设置
     */
    private void selectDefaultPage() {
        TreeItem<String> root = settingsTree.getRoot();
        if (root != null && !root.getChildren().isEmpty()) {
            TreeItem<String> firstItem = root.getChildren().get(0);
            settingsTree.getSelectionModel().select(0); // 选择第一个索引
            // settingsTree.scrollTo(firstItem); // scrollTo 需要 int 参数，暂时移除
        }
    }
    
    /**
     * 应用设置
     */
    public void applySettings() {
        log.info("应用设置...");
        loadedControllers.values().forEach(controller -> {
            if (controller instanceof SettingsGeneralController general) general.saveSettings();
            else if (controller instanceof SettingsSSHController ssh) ssh.saveSettings();
            else if (controller instanceof SettingsAIController ai) ai.saveSettings();
        });
    }

    /**
     * 仅应用设置，不关闭对话框
     */
    @FXML
    private void applySettingsOnly() {
        try {
            applySettings();
        } catch (RuntimeException e) {
            log.error("应用设置失败", e);
        }
    }
    
    /**
     * 应用主题设置
     */
    public void applyTheme(String theme) {
        log.info("应用主题：{}", theme);
        
        try {
            Parent root = contentContainer;
            while (root.getParent() != null) {
                root = root.getParent();
            }

            if (root.getScene() == null) {
                log.warn("无法获取 Scene，主题切换失败");
                return;
            }

            var tm = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            var themeObj = switch (theme) {
                case "浅色" -> com.runzhi.aishell.common.util.ThemeManager.Theme.LIGHT;
                case "深色" -> com.runzhi.aishell.common.util.ThemeManager.Theme.DARK;
                default -> com.runzhi.aishell.common.util.ThemeManager.Theme.DARK;
            };
            tm.applyTheme(root.getScene(), themeObj);
            log.info("主题切换成功：{}", themeObj.getDisplayName());
        } catch (Exception e) {
            log.error("主题切换失败", e);
        }
    }
    
    /**
     * 确定并关闭对话框
     */
    @FXML
    private void okAndClose() {
        log.info("保存设置并关闭对话框");
        try {
            applySettings();
        } catch (RuntimeException e) {
            log.error("保存设置失败", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.tr("settings.save.failed"));
            alert.setHeaderText(null);
            alert.setContentText(e.getMessage() == null ? I18n.tr("settings.save.fallback") : e.getMessage());
            alert.showAndWait();
            return;
        }
        
        // 关闭对话框
        if (contentContainer.getScene() != null && contentContainer.getScene().getWindow() != null) {
            ((javafx.stage.Stage) contentContainer.getScene().getWindow()).close();
        }
    }
    
    /**
     * 取消并关闭对话框
     */
    @FXML
    private void cancelAndClose() {
        log.info("取消设置更改");
        
        // 关闭对话框
        if (contentContainer.getScene() != null && contentContainer.getScene().getWindow() != null) {
            ((javafx.stage.Stage) contentContainer.getScene().getWindow()).close();
        }
    }
}
