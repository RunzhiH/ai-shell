/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.monitor.domain.SystemMetrics;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.PieChart;
import javafx.scene.layout.GridPane;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * 高级监控图表控制器 - 提供环形图、饼图等高级可视化组件
 */
@Slf4j
public class AdvancedMonitorChartsController extends BaseController implements Initializable {
    
    @FXML
    private GridPane chartsContainer;
    
    @FXML
    private PieChart resourcePieChart;
    
    @FXML
    private PieChart processTypePieChart;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        super.initialize(location, resources);

        initializeCharts();

        // 订阅监控面板发布的最新指标：此前打开后图表永远为空（2026-10-23 审计 UI-2）
        com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters()
                .addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                    if ("last_system_metrics".equals(change.getKey()) && change.wasAdded()
                            && change.getValueAdded() instanceof SystemMetrics metrics) {
                        updateResourcePieChart(metrics);
                    }
                });
        // 打开时用最近一次指标填充（若已有）
        Object latest = com.runzhi.aishell.common.util.ParameterManager.getInstance().get("last_system_metrics");
        if (latest instanceof SystemMetrics metrics) {
            updateResourcePieChart(metrics);
        }

        log.info("高级监控图表初始化完成");
    }
    
    /**
     * 初始化图表
     */
    private void initializeCharts() {
        // 初始化资源饼图
        if (resourcePieChart != null) {
            resourcePieChart.setTitle("系统资源分布");
            resourcePieChart.setLegendVisible(true);
            resourcePieChart.setClockwise(false);
        }
        
        // 初始化进程类型饼图
        if (processTypePieChart != null) {
            processTypePieChart.setTitle("进程类型分布");
            processTypePieChart.setLegendVisible(true);
            processTypePieChart.setClockwise(false);
        }
    }
    
    /**
     * 更新资源饼图数据
     */
    public void updateResourcePieChart(SystemMetrics metrics) {
        
        Platform.runLater(() -> {
            if (resourcePieChart == null) return;
            
            resourcePieChart.getData().clear();
            
            // CPU 使用率
            double cpuPercent = metrics.getCpuUser();
            PieChart.Data cpuData = new PieChart.Data("CPU: " + String.format("%.1f", cpuPercent) + "%", cpuPercent);
            
            // 内存使用率
            double memoryPercent = 0;
            if (metrics.getTotalMemory() > 0) {
                memoryPercent = (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100;
            }
            PieChart.Data memoryData = new PieChart.Data("内存：" + String.format("%.1f", memoryPercent) + "%", memoryPercent);
            
            // 磁盘使用率
            PieChart.Data diskData = new PieChart.Data("磁盘：" + String.format("%.1f", metrics.getDiskUsage()) + "%", metrics.getDiskUsage());
            
            // Swap 使用率
            double swapPercent = 0;
            if (metrics.getTotalSwap() > 0) {
                swapPercent = (double) metrics.getUsedSwap() / metrics.getTotalSwap() * 100;
            }
            PieChart.Data swapData = new PieChart.Data("Swap: " + String.format("%.1f", swapPercent) + "%", swapPercent);
            
            resourcePieChart.getData().addAll(cpuData, memoryData, diskData, swapData);
            updateProcessTypePieChart(metrics);
        });
    }
    
    /**
     * 更新进程类型饼图（运行/睡眠/停止/僵尸，来自 top 解析的真实任务计数）
     */
    public void updateProcessTypePieChart(SystemMetrics metrics) {
        Platform.runLater(() -> {
            if (processTypePieChart == null) return;

            processTypePieChart.getData().clear();

            // 真实任务状态计数取代此前写死的 25/50/15/10（2026-10-23 审计 UI-2）
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
            if (resourcePieChart != null) {
                resourcePieChart.getData().clear();
            }
            if (processTypePieChart != null) {
                processTypePieChart.getData().clear();
            }
        });
    }
}
