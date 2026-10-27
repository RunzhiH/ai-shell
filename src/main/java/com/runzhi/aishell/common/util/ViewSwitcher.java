/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 视图切换辅助类
 * 
 * <p>提供统一的视图加载、切换和内容替换功能。
 * 简化控制器中的视图管理代码。
 */
@Slf4j
public class ViewSwitcher {

    /** 已知的视图级样式类：切换时先全部移除，避免无限累积。 */
    private static final java.util.List<String> VIEW_STYLE_CLASSES =
            java.util.List.of("terminal-view", "monitor-view", "bookmark-view", "file-manager-view",
                    "connection-view", "settings-view", "ai-view", "log-view", "process-view");

    private final AIShellModuleManager moduleManager;
    private final StackPane contentArea;

    /**
     * 创建 ViewSwitcher 实例
     *
     * @param contentArea 内容区域容器
     */
    public ViewSwitcher(StackPane contentArea) {
        this.moduleManager = AIShellModuleManager.getInstance();
        this.contentArea = contentArea;
    }

    /**
     * 切换到指定视图
     *
     * @param fxmlPath   FXML 文件路径
     * @param styleClass 样式类名
     * @return 加载的视图根节点
     */
    public Parent switchTo(String fxmlPath, String styleClass) {
        try {
            Parent view = moduleManager.getNavigationManager().loadPage(fxmlPath);
            replaceContent(view, styleClass);
            return view;
        } catch (IOException e) {
            log.error("Failed to switch to view: {}", fxmlPath, e);
            return null;
        }
    }

    /**
     * 切换到指定视图并配置控制器
     *
     * @param fxmlPath        FXML 文件路径
     * @param styleClass      样式类名
     * @param controllerClass 控制器类型
     * @param configurator    控制器配置器
     * @param <T>             控制器类型
     * @return 加载的视图根节点
     */
    public <T> Parent switchTo(String fxmlPath, String styleClass, 
                               Class<T> controllerClass, Consumer<T> configurator) {
        try {
            Parent view = moduleManager.getNavigationManager().loadPage(fxmlPath);
            
            Object controller = moduleManager.getNavigationManager().getController(fxmlPath);
            if (controllerClass.isInstance(controller)) {
                configurator.accept(controllerClass.cast(controller));
            }
            
            replaceContent(view, styleClass);
            return view;
        } catch (IOException e) {
            log.error("Failed to switch to view: {}", fxmlPath, e);
            return null;
        }
    }

    /**
     * 异步切换到指定视图
     *
     * @param fxmlPath   FXML 文件路径
     * @param styleClass 样式类名
     * @param callback   完成回调
     */
    public void switchToAsync(String fxmlPath, String styleClass, 
                              java.util.function.Consumer<Parent> callback) {
        Platform.runLater(() -> {
            Parent view = switchTo(fxmlPath, styleClass);
            if (callback != null && view != null) {
                callback.accept(view);
            }
        });
    }

    /**
     * 异步切换到指定视图并配置控制器
     *
     * @param fxmlPath        FXML 文件路径
     * @param styleClass      样式类名
     * @param controllerClass 控制器类型
     * @param configurator    控制器配置器
     * @param callback        完成回调
     * @param <T>             控制器类型
     */
    public <T> void switchToAsync(String fxmlPath, String styleClass,
                                  Class<T> controllerClass, Consumer<T> configurator,
                                  Consumer<Parent> callback) {
        Platform.runLater(() -> {
            Parent view = switchTo(fxmlPath, styleClass, controllerClass, configurator);
            if (callback != null && view != null) {
                callback.accept(view);
            }
        });
    }

    /**
     * 替换内容区域
     *
     * @param content    新内容
     * @param styleClass 样式类名
     */
    public void replaceContent(Parent content, String styleClass) {
        if (contentArea == null || content == null) {
            return;
        }

        contentArea.getChildren().clear();
        contentArea.getChildren().add(content);

        // 设置内容自适应
        if (content instanceof Region) {
            Region region = (Region) content;
            region.prefWidthProperty().bind(contentArea.widthProperty());
            region.prefHeightProperty().bind(contentArea.heightProperty());
        }

        // 应用样式类：先移除其他视图切换加入的样式类，否则每次切换都会累积（2026-10-23 审计 UI-4）
        if (styleClass != null && !styleClass.isEmpty()) {
            VIEW_STYLE_CLASSES.forEach(contentArea.getStyleClass()::remove);
            contentArea.getStyleClass().add(styleClass);
        }

        log.debug("Content replaced with: {}", content.getClass().getSimpleName());
    }

    /**
     * 清空内容区域
     */
    public void clearContent() {
        if (contentArea != null) {
            contentArea.getChildren().clear();
        }
    }

    /**
     * 获取控制器
     *
     * @param fxmlPath       FXML 文件路径
     * @param controllerClass 控制器类型
     * @param <T>            控制器类型
     * @return 控制器实例，如果不存在则返回 null
     */
    public <T> T getController(String fxmlPath, Class<T> controllerClass) {
        Object controller = moduleManager.getNavigationManager().getController(fxmlPath);
        if (controllerClass.isInstance(controller)) {
            return controllerClass.cast(controller);
        }
        return null;
    }

    /**
     * 延迟执行视图操作
     *
     * @param delayMs 延迟时间（毫秒）
     * @param action  要执行的操作
     */
    public void delayedAction(long delayMs, Runnable action) {
        javafx.animation.PauseTransition pause = 
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(delayMs));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    /**
     * 延迟切换视图
     *
     * @param fxmlPath   FXML 文件路径
     * @param styleClass 样式类名
     * @param delayMs    延迟时间（毫秒）
     * @param callback   完成回调
     */
    public void delayedSwitch(String fxmlPath, String styleClass, 
                              long delayMs, Consumer<Parent> callback) {
        delayedAction(delayMs, () -> {
            Parent view = switchTo(fxmlPath, styleClass);
            if (callback != null && view != null) {
                callback.accept(view);
            }
        });
    }
}