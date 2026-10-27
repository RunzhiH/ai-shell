/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.scene.control.Alert;
import javafx.scene.layout.Region;

/**
 * 对话框工具类，提供通用的对话框创建和显示方法
 */
public class DialogUtils {
    
    /**
     * 显示信息提示对话框
     *
     * @param title 标题
     * @param headerText 头部文本
     * @param contentText 内容文本
     */
    public static void showInfoDialog(String title, String headerText, String contentText) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(headerText);
            alert.setContentText(contentText);
            
            // 设置对话框最小尺寸以防止内容被截断
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            
            alert.showAndWait();
        });
    }
    
    /**
     * 显示错误对话框
     *
     * @param title 标题
     * @param headerText 头部文本
     * @param contentText 内容文本
     */
    public static void showErrorDialog(String title, String headerText, String contentText) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(headerText);
            alert.setContentText(contentText);
            
            // 设置对话框最小尺寸以防止内容被截断
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            
            alert.showAndWait();
        });
    }
    



}