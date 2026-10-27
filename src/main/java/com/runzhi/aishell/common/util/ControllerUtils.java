/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import lombok.extern.slf4j.Slf4j;

/**
 * 控制器工具类 - 提供通用的 UI 操作和对话框功能
 * 替代 BaseController 中的重复方法
 */
@Slf4j
public class ControllerUtils {

    private ControllerUtils() {
        // 私有构造函数，防止实例化
    }

    /**
     * 显示信息提示（JavaFX 线程安全）
     */
    public static void showInfoAlert(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            setDialogStyle(alert.getDialogPane());
            alert.showAndWait();
        });
    }

    /**
     * 显示错误提示（JavaFX 线程安全）
     */
    public static void showErrorAlert(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            setDialogStyle(alert.getDialogPane());
            alert.showAndWait();
        });
    }

    /**
     * 显示警告提示（JavaFX 线程安全）
     */
    public static void showWarningAlert(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            setDialogStyle(alert.getDialogPane());
            alert.showAndWait();
        });
    }

    /**
     * 显示确认对话框
     * @return true 表示确认，false 表示取消
     */
    public static boolean showConfirmDialog(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        setDialogStyle(alert.getDialogPane());
        return alert.showAndWait().filter(response -> response == ButtonType.OK).isPresent();
    }

    /**
     * 设置对话框样式
     */
    private static void setDialogStyle(DialogPane dialogPane) {
        // 自动调整大小
        dialogPane.getScene().getWindow().sizeToScene();
        
        // 应用 CSS 样式（如果有）
        try {
            var scene = dialogPane.getScene();
            if (scene != null) {
                scene.getRoot().getStylesheets().clear();
                var tm = com.runzhi.aishell.common.util.ThemeManager.getInstance();
                for (String sheet : tm.getStylesheetsFor(tm.getCurrentTheme())) {
                    if (sheet != null) {
                        scene.getRoot().getStylesheets().add(sheet);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply dialog style", e);
        }
    }

    /**
     * 获取模块管理器实例（简化调用）
     */
    public static AIShellModuleManager getModuleManager() {
        return AIShellModuleManager.getInstance();
    }

    /**
     * 在 JavaFX 线程执行
     */
    public static void runLater(Runnable runnable) {
        Platform.runLater(runnable);
    }

    /**
     * 异步执行（后台线程）
     */
    public static void runAsync(Runnable runnable) {
        new Thread(runnable).start();
    }

    /**
     * 异步执行并处理异常
     */
    public static void runAsync(Runnable runnable, String errorMessage) {
        new Thread(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                log.error(errorMessage, e);
                Platform.runLater(() -> showErrorAlert("错误", errorMessage + ": " + e.getMessage()));
            }
        }).start();
    }
}
