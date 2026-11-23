/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.monitor.domain.SystemMetrics;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * 高级监控图表控制器。
 *
 * <p>「系统资源分布」此前把 CPU/内存/磁盘/Swap 的百分比混进同一个饼图，
 * 这些指标没有共同分母（方案 §2 改造点 5）；已改为各自独立的资源条，
 * 在每条上标注当前值与口径。进程状态分布保留饼图，其分母为进程总数。
 */
@Slf4j
public class AdvancedMonitorChartsController extends BaseController implements Initializable {

    @FXML
    private VBox resourceBarsBox;

    @FXML
    private PieChart processTypePieChart;

    /** 四个独立资源条：CPU / 内存 / 磁盘容量 / Swap。 */
    private ProgressBar cpuResourceBar;
    private ProgressBar memoryResourceBar;
    private ProgressBar diskResourceBar;
    private ProgressBar swapResourceBar;
    private Label cpuResourceLabel;
    private Label memoryResourceLabel;
    private Label diskResourceLabel;
    private Label swapResourceLabel;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 本控制器不使用模块管理器：跳过 super.initialize 避免弹出窗口加载时
        // 触发与资源分析无关的单例初始化链（与 MonitorPanelController 一致）
        initializeCharts();

        // 订阅监控面板发布的最新指标
        com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters()
                .addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                    if ("last_system_metrics".equals(change.getKey()) && change.wasAdded()
                            && change.getValueAdded() instanceof SystemMetrics metrics) {
                        updateResourceBars(metrics);
                    }
                });
        // 打开时用最近一次指标填充（若已有）
        Object latest = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("last_system_metrics");
        if (latest instanceof SystemMetrics metrics) {
            updateResourceBars(metrics);
        }

        log.info("高级监控图表初始化完成");
    }

    /**
     * 初始化图表与资源条。
     */
    private void initializeCharts() {
        if (resourceBarsBox != null) {
            cpuResourceBar = buildResourceBar("CPU 使用率（单核口径）", 0);
            memoryResourceBar = buildResourceBar("内存使用率（已用/总量）", 1);
            diskResourceBar = buildResourceBar("磁盘容量使用率（df 最大）", 2);
            swapResourceBar = buildResourceBar("Swap 使用率（已用/总量）", 3);
        }

        if (processTypePieChart != null) {
            processTypePieChart.setTitle("进程状态分布");
            processTypePieChart.setLegendVisible(true);
            processTypePieChart.setClockwise(false);
        }
    }

    /** 构造一行资源条：名称、当前值与口径说明、独立进度条。 */
    private ProgressBar buildResourceBar(String name, int index) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("text-secondary");
        Label valueLabel = new Label("—");
        valueLabel.getStyleClass().add("monitor-value");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        bar.getStyleClass().add("resource-bar");
        HBox row = new HBox(12, nameLabel, bar, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        resourceBarsBox.getChildren().add(index, row);
        switch (index) {
            case 0 -> cpuResourceLabel = valueLabel;
            case 1 -> memoryResourceLabel = valueLabel;
            case 2 -> diskResourceLabel = valueLabel;
            case 3 -> swapResourceLabel = valueLabel;
        }
        return bar;
    }

    /**
     * 用最新指标更新资源条与进程状态饼图（供参数总线监听与外部调用）。
     */
    public void updateResourceBars(SystemMetrics metrics) {
        Platform.runLater(() -> {
            if (metrics == null) return;

            if (cpuResourceBar != null) {
                double cpu = clampPercent(metrics.getCpuTotalUsed());
                cpuResourceBar.setProgress(cpu / 100.0);
                if (cpuResourceLabel != null) {
                    cpuResourceLabel.setText(metrics.getCpuTotalUsed() <= 0 && metrics.getCpuIdle() <= 0
                            ? "采样中" : String.format("%.1f%%", cpu));
                }
                applyBarStyle(cpuResourceBar, cpu, 70, 90);
            }
            if (memoryResourceBar != null) {
                double memoryPercent = metrics.getTotalMemory() > 0
                        ? (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100 : 0;
                memoryResourceBar.setProgress(memoryPercent / 100.0);
                if (memoryResourceLabel != null) {
                    memoryResourceLabel.setText(metrics.getTotalMemory() > 0
                            ? String.format("%.1f%%", memoryPercent) : "—");
                }
                applyBarStyle(memoryResourceBar, memoryPercent, 75, 90);
            }
            if (diskResourceBar != null) {
                double disk = metrics.getDiskUsage();
                diskResourceBar.setProgress(disk / 100.0);
                if (diskResourceLabel != null) {
                    diskResourceLabel.setText(String.format("%.1f%%", disk));
                }
                applyBarStyle(diskResourceBar, disk, 80, 95);
            }
            if (swapResourceBar != null) {
                double swapPercent = metrics.getTotalSwap() > 0
                        ? (double) metrics.getUsedSwap() / metrics.getTotalSwap() * 100 : 0;
                swapResourceBar.setProgress(swapPercent / 100.0);
                if (swapResourceLabel != null) {
                    swapResourceLabel.setText(metrics.getTotalSwap() > 0
                            ? String.format("%.1f%%", swapPercent) : "未启用");
                }
                applyBarStyle(swapResourceBar, swapPercent, 75, 90);
            }

            updateProcessTypePieChart(metrics);
        });
    }

    private static double clampPercent(double percent) {
        if (Double.isNaN(percent) || Double.isInfinite(percent) || percent < 0) return 0;
        return Math.min(100, percent);
    }

    private static void applyBarStyle(ProgressBar bar, double percent, double warn, double critical) {
        bar.getStyleClass().removeAll("progress-normal", "progress-warning", "progress-critical");
        if (percent >= critical) {
            bar.getStyleClass().add("progress-critical");
        } else if (percent >= warn) {
            bar.getStyleClass().add("progress-warning");
        } else {
            bar.getStyleClass().add("progress-normal");
        }
    }

    /**
     * 更新进程状态饼图（运行/睡眠/停止/僵尸，来自 ps 采样的真实任务计数）
     */
    public void updateProcessTypePieChart(SystemMetrics metrics) {
        Platform.runLater(() -> {
            if (processTypePieChart == null) return;

            processTypePieChart.getData().clear();

            if (metrics.getTotalTasks() <= 0 && metrics.getRunningTasks() <= 0 && metrics.getSleepingTasks() <= 0) {
                processTypePieChart.setTitle("进程状态分布（等待采样）");
                return;
            }
            processTypePieChart.setTitle("进程状态分布");
            processTypePieChart.getData().add(new PieChart.Data("运行", metrics.getRunningTasks()));
            processTypePieChart.getData().add(new PieChart.Data("睡眠", metrics.getSleepingTasks()));
            processTypePieChart.getData().add(new PieChart.Data("停止", metrics.getStoppedTasks()));
            processTypePieChart.getData().add(new PieChart.Data("僵尸", metrics.getZombieTasks()));
        });
    }

    /**
     * 清空图表数据
     */
    public void clearCharts() {
        Platform.runLater(() -> {
            if (processTypePieChart != null) {
                processTypePieChart.getData().clear();
            }
            if (cpuResourceBar != null) {
                cpuResourceBar.setProgress(0);
                memoryResourceBar.setProgress(0);
                diskResourceBar.setProgress(0);
                swapResourceBar.setProgress(0);
            }
        });
    }
}
