/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import com.runzhi.aishell.common.logging.LoggerManager;
import com.runzhi.aishell.utils.ShellUtils;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.text.TextFlow;

import java.net.URL;

/**
 * UI工具类，用于统一管理UI相关操作
 */
public class UITools {

    /**
     * 为TextFlow添加带颜色的文本
     *
     * @param textFlow TextFlow控件
     * @param text     要添加的文本
     * @param color    文本颜色
     */
    public static void appendColoredTextToFlow(TextFlow textFlow, String text, String color) {
        ShellUtils.appendColoredTextToFlow(textFlow, text, color);
    }

    /**
     * 为对话框设置CSS样式
     *
     * @param dialogPane 对话框面板
     * @return 是否成功设置样式
     */
    public static boolean setDialogCSS(DialogPane dialogPane) {
        try {
            var tm = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            String css = tm.resolvePublic(tm.getCurrentTheme().getCssPath());
            if (css == null) {
                css = tm.resolvePublic("/com/runzhi/aishell/style.css");
            }
            if (css != null) {
                dialogPane.getStylesheets().add(css);
                return true;
            }
            LoggerManager.getLogger(UITools.class).warn("CSS主题样式未找到");
            return false;
        } catch (Exception e) {
            LoggerManager.getLogger(UITools.class).warn("设置对话框样式失败", e);
            return false;
        }
    }

    /**
     * 为图表设置白色主题
     *
     * @param chart 图表控件
     */
    public static void setChartWhiteTheme(LineChart<Number, Number> chart) {
        if (chart != null) {
            // 设置图表背景色为白色
            chart.getStyleClass().add("white-background");

            Platform.runLater(() -> {
                // 设置坐标轴颜色 - 需要检查lookup返回的节点是否为null
                Node plotBackground = chart.lookup(".chart-plot-background");
                if (plotBackground != null) {
                    plotBackground.getStyleClass().add("white-background");
                }

                Node axis = chart.lookup(".axis");
                if (axis != null) {
                    axis.getStyleClass().add("black-text");
                }

                Node axisLabel = chart.lookup(".axis-label");
                if (axisLabel != null) {
                    axisLabel.getStyleClass().add("black-text");
                }

                // 设置网格线颜色
                Node horizontalGridLine = chart.lookup(".chart-grid-line-horizontal");
                if (horizontalGridLine != null) {
                    horizontalGridLine.setStyle("-fx-stroke: #eee;");
                }

                Node verticalGridLine = chart.lookup(".chart-grid-line-vertical");
                if (verticalGridLine != null) {
                    verticalGridLine.setStyle("-fx-stroke: #eee;");
                }

                // 设置图表标题和图例颜色
                Node chartTitle = chart.lookup(".chart-title");
                if (chartTitle != null) {
                    chartTitle.getStyleClass().add("black-text");
                }

                Node chartLegend = chart.lookup(".chart-legend");
                if (chartLegend != null) {
                    chartLegend.getStyleClass().add("black-text");
                }
            });
        }
    }

    /**
     * 为容器设置深色主题
     *
     * @param pane 容器控件
     */
    public static void setDarkTheme(Pane pane) {
        pane.getStyleClass().add("dark-theme");
    }

    /**
     * 为标签设置白色文本样式
     *
     * @param label 标签控件
     */
    public static void setWhiteText(Label label) {
        label.getStyleClass().add("white-text");
    }

    /**
     * 为标签设置欢迎样式
     *
     * @param label 标签控件
     */
    public static void setWelcomeStyle(Label label) {
        label.getStyleClass().add("welcome-label");
    }

    /**
     * 为标签设置提示样式
     *
     * @param label 标签控件
     */
    public static void setPromptStyle(Label label) {
        label.getStyleClass().add("prompt-label");
    }
}