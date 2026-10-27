/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.UITools;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@Slf4j
public class MonitorPanelController extends BaseController implements Initializable {

    // 告警阈值配置（百分比）
    private static final double CPU_WARNING_THRESHOLD = 70.0;      // 警告阈值
    private static final double CPU_CRITICAL_THRESHOLD = 90.0;     // 严重阈值
    private static final double MEMORY_WARNING_THRESHOLD = 75.0;   // 警告阈值
    private static final double MEMORY_CRITICAL_THRESHOLD = 90.0;  // 严重阈值
    private static final double DISK_WARNING_THRESHOLD = 80.0;     // 警告阈值
    private static final double DISK_CRITICAL_THRESHOLD = 95.0;    // 严重阈值

    // 图表配置
    private static final int MAX_DATA_POINTS = 60;                  // 最大数据点数
    private static final long CHART_TIME_RANGE = 60000;             // 图表显示时间范围（毫秒）
    private static final int REFRESH_INTERVAL_MS = 1000;            // 刷新间隔（毫秒）

    /** FXML 声明了这三个 metric-value 标签但此前控制器没有对应字段，卡片永远显示 "—"（2026-10-23 审计 UI-1） */
    @FXML
    private Label cpuPercentLabel;
    @FXML
    private Label memoryPercentLabel;
    @FXML
    private Label diskPercentLabel;

    @FXML
    private ProgressBar cpuProgress;

    @FXML
    private ProgressBar memoryProgress;

    @FXML
    private ProgressBar diskProgress;

    @FXML
    private Label netUpLabel;

    @FXML
    private Label netDownLabel;

    @FXML
    private Label loadLabel;
    
    @FXML
    private Label connectionsLabel;
    
    @FXML
    private Button refreshBtn;
    
    // 系统信息标签
    @FXML
    private Label uptimeLabel;
    
    @FXML
    private Label cpuLabel;
    
    @FXML
    private Label memoryLabel;
    
    @FXML
    private Label swapLabel;
    
    // 存储信息表格
    @FXML
    private TableView<Map<String, String>> storageTable;
    
    @FXML
    private TableColumn<Map<String, String>, String> pathColumn;
    
    @FXML
    private TableColumn<Map<String, String>, String> sizeColumn;

    // 图表
    @FXML
    private LineChart<Number, Number> cpuChart;
    @FXML
    private LineChart<Number, Number> memoryChart;
    @FXML
    private LineChart<Number, Number> diskChart;
    @FXML
    private LineChart<Number, Number> networkChart;

    @FXML
    private NumberAxis cpuXAxis;
    @FXML
    private NumberAxis cpuYAxis;
    @FXML
    private NumberAxis memoryXAxis;
    @FXML
    private NumberAxis memoryYAxis;
    @FXML
    private NumberAxis diskXAxis;
    @FXML
    private NumberAxis diskYAxis;
    @FXML
    private NumberAxis networkXAxis;
    @FXML
    private NumberAxis networkYAxis;

    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> diskSeries;
    private XYChart.Series<Number, Number> networkUpSeries;
    private XYChart.Series<Number, Number> networkDownSeries;


    // 为每个Tab维护独立的系统监控器
    private final Map<String, SystemMonitor> tabSystemMonitors = new java.util.concurrent.ConcurrentHashMap<>();
    private SystemMonitor currentSystemMonitor;
    private SSHServiceImpl sshService;
    private String currentTabId;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化历史图表
        initializeCharts();


        // 设置白色主题样式
        Platform.runLater(this::setChartsWhiteTheme);
        
        // 设置刷新按钮事件
        if (refreshBtn != null) {
            refreshBtn.setOnAction(e -> refreshMonitoring());
        }
        
        // 初始化存储表格
        initializeStorageTable();
        
        // 监听模块管理器中的当前激活 Tab 变化
        if (moduleManager != null) {
            com.runzhi.aishell.common.util.ParameterManager.getInstance().getParameters().addListener((javafx.collections.MapChangeListener<? super String, ? super Object>) change -> {
                if (change.getKey().equals("current_active_tab") && change.wasAdded()) {
                    String tabId = (String) change.getValueAdded();
                    Platform.runLater(() -> {
                        setSystemMonitorForTab(tabId);
                    });
                }
            });
        }
    }

    @FXML
    public void refreshMonitoring() {
        // 重新启动监控
        if (currentSystemMonitor != null) {
            currentSystemMonitor.stopMonitoring();
            currentSystemMonitor.startMonitoring();
            if (currentTabId != null) updateStorageInfo(currentTabId);
        }
    }

    private void setChartsWhiteTheme() {
        setCpuChartWhiteTheme();
        setMemoryChartWhiteTheme();
        setDiskChartWhiteTheme();
        setNetworkChartWhiteTheme();
    }

    private void setCpuChartWhiteTheme() {
        UITools.setChartWhiteTheme(cpuChart);
    }

    private void setMemoryChartWhiteTheme() {
        UITools.setChartWhiteTheme(memoryChart);
    }

    private void setDiskChartWhiteTheme() {
        UITools.setChartWhiteTheme(diskChart);
    }

    private void setNetworkChartWhiteTheme() {
        UITools.setChartWhiteTheme(networkChart);
    }

    /**
     * 为指定的Tab设置系统监控器
     */
    public void setSystemMonitorForTab(String tabId) {
        currentTabId = tabId;
        if (sshService != null && tabId != null) {
            SSHConnectionManager connection = sshService.getTabConnection(tabId);
            if (connection != null && connection.isConnected()) {
                // 检查是否已有对应Tab的监控器
                SystemMonitor tabMonitor = tabSystemMonitors.computeIfAbsent(tabId, k -> new SystemMonitor(connection));
                // 创建新的监控器

                // 停止当前监控器
                if (currentSystemMonitor != null && currentSystemMonitor != tabMonitor) {
                    currentSystemMonitor.stopMonitoring();
                }

                // 设置新的监控器
                currentSystemMonitor = tabMonitor;
                // 设置监控回调
                currentSystemMonitor.setCallback(this::updateMetrics);
                // 启动监控
                currentSystemMonitor.startMonitoring();
                updateStorageInfo(tabId);
                
                log.info("Started monitoring for tab: {}", tabId);
            } else {
                // 如果连接不存在或未连接，停止监控
                if (currentSystemMonitor != null) {
                    currentSystemMonitor.stopMonitoring();
                    currentSystemMonitor = null;
                }
                clearMetricsDisplay(); // 清空显示
                
                log.info("Stopped monitoring for tab: {} (not connected)", tabId);
            }
        } else {
            // 如果没有有效的参数，停止监控
            if (currentSystemMonitor != null) {
                currentSystemMonitor.stopMonitoring();
                currentSystemMonitor = null;
            }
            clearMetricsDisplay(); // 清空显示
            
            log.info("Stopped monitoring (invalid parameters)");
        }
    }

    /**
     * 清空监控数据显示
     */
    public void clearMetricsDisplay() {
        Platform.runLater(() -> {
            cpuProgress.setProgress(0);
            memoryProgress.setProgress(0);
            diskProgress.setProgress(0);
            if (cpuPercentLabel != null) cpuPercentLabel.setText("—");
            if (memoryPercentLabel != null) memoryPercentLabel.setText("—");
            if (diskPercentLabel != null) diskPercentLabel.setText("—");
            netUpLabel.setText("0 KB/s");
            netDownLabel.setText("0 KB/s");
            if (loadLabel != null) {
                loadLabel.setText("Load: 0.00, 0.00, 0.00");
            }
            if (connectionsLabel != null) {
                connectionsLabel.setText("ESTABLISHED: 0, LISTEN: 0");
            }
            if (uptimeLabel != null) {
                uptimeLabel.setText("0天 0小时");
            }
            if (cpuLabel != null) {
                cpuLabel.setText("0%");
            }
            if (memoryLabel != null) {
                memoryLabel.setText("0/0 (0%)");
            }
            if (swapLabel != null) {
                swapLabel.setText("0/0 (0%)");
            }

            // 清空图表数据
            if (cpuSeries != null) cpuSeries.getData().clear();
            if (memorySeries != null) memorySeries.getData().clear();
            if (diskSeries != null) diskSeries.getData().clear();
            if (networkUpSeries != null) networkUpSeries.getData().clear();
            if (networkDownSeries != null) networkDownSeries.getData().clear();
            
            // 清空存储表格
            if (storageTable != null) {
                storageTable.getItems().clear();
            }
        });
    }

    private void initializeCharts() {
        // 初始化CPU使用率图表
        if (cpuChart != null) {
            cpuSeries = new XYChart.Series<>();
            cpuSeries.setName("CPU使用率");
            cpuChart.getData().add(cpuSeries);
            cpuChart.setCreateSymbols(false); // 不显示数据点符号，只显示线条
            // 移除固定范围设置，让图表自动缩放
            if (cpuXAxis != null) {
                cpuXAxis.setLabel("时间");
            }
            if (cpuYAxis != null) {
                cpuYAxis.setLabel("%");
                cpuYAxis.setUpperBound(100);
            }
        }

        // 初始化内存使用率图表
        if (memoryChart != null) {
            memorySeries = new XYChart.Series<>();
            memorySeries.setName("内存使用率");
            memoryChart.getData().add(memorySeries);
            memoryChart.setCreateSymbols(false);
            if (memoryXAxis != null) {
                memoryXAxis.setLabel("时间");
            }
            if (memoryYAxis != null) {
                memoryYAxis.setLabel("%");
                memoryYAxis.setUpperBound(100);
            }
        }

        // 初始化磁盘使用率图表
        if (diskChart != null) {
            diskSeries = new XYChart.Series<>();
            diskSeries.setName("磁盘使用率");
            diskChart.getData().add(diskSeries);
            diskChart.setCreateSymbols(false);
            if (diskXAxis != null) {
                diskXAxis.setLabel("时间");
            }
            if (diskYAxis != null) {
                diskYAxis.setLabel("%");
                diskYAxis.setUpperBound(100);
            }
        }

        // 初始化网络流量图表
        if (networkChart != null) {
            networkUpSeries = new XYChart.Series<>();
            networkUpSeries.setName("上行");
            networkDownSeries = new XYChart.Series<>();
            networkDownSeries.setName("下行");
            networkChart.getData().addAll(networkUpSeries, networkDownSeries);
            networkChart.setCreateSymbols(false);
            if (networkXAxis != null) {
                networkXAxis.setLabel("时间");
            }
            if (networkYAxis != null) {
                networkYAxis.setLabel("KB/s");
            }
        }
    }
    private void initializeStorageTable() {
        // 初始化存储表格列
        if (pathColumn != null) {
            pathColumn.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get("path")));
        }
        if (sizeColumn != null) {
            sizeColumn.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get("size")));
        }
        
        if (storageTable != null) storageTable.setItems(FXCollections.observableArrayList());
    }
    
    private void updateStorageInfo(String tabId) {
        SSHConnectionManager connection = sshService == null ? null : sshService.getTabConnection(tabId);
        if (connection == null || !connection.isConnected()) return;
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool().execute(() -> {
            try {
                String output = connection.executeCommand("LC_ALL=C df -Pk");
                ObservableList<Map<String, String>> storageData = FXCollections.observableArrayList();
                for (String line : output.split("\\R")) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length < 6 || !fields[1].matches("\\d+")) continue;
                    Map<String, String> row = new HashMap<>();
                    row.put("path", fields[fields.length - 1]);
                    row.put("size", formatKiB(fields[2]) + " / " + formatKiB(fields[1]));
                    storageData.add(row);
                }
                Platform.runLater(() -> {
                    if (java.util.Objects.equals(currentTabId, tabId) && storageTable != null) {
                        storageTable.setItems(storageData);
                    }
                });
            } catch (Exception e) {
                log.warn("Unable to load storage metrics for tab {}: {}", tabId, e.getMessage());
            }
        });
    }

    private String formatKiB(String value) {
        try {
            double kib = Double.parseDouble(value);
            if (kib >= 1024 * 1024) return String.format("%.1f GB", kib / (1024 * 1024));
            if (kib >= 1024) return String.format("%.1f MB", kib / 1024);
            return String.format("%.0f KB", kib);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    public void removeSystemMonitorForTab(String tabId) {
        SystemMonitor monitor = tabSystemMonitors.remove(tabId);
        if (monitor != null) monitor.stopMonitoring();
        if (monitor == currentSystemMonitor) {
            currentSystemMonitor = null;
            currentTabId = null;
            clearMetricsDisplay();
        }
    }

    private void updateMetrics(SystemMetrics metrics) {
        // 把最新指标发布到参数总线：高级监控图表窗口订阅它来填充饼图（2026-10-23 审计 UI-2）
        com.runzhi.aishell.common.util.ParameterManager.getInstance().put("last_system_metrics", metrics);
        Platform.runLater(() -> {
            // CPU 使用率应该是一个 0-100 的百分比值，转换为 0-1 的进度值
            cpuProgress.setProgress(metrics.getCpuUser() / 100.0);
            if (cpuPercentLabel != null) {
                cpuPercentLabel.setText(String.format("%.1f%%", metrics.getCpuUser()));
            }
            // 内存使用率：已用内存 / 总内存
            if (metrics.getTotalMemory() > 0) {
                double memPct = (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100;
                memoryProgress.setProgress(memPct / 100.0);
                if (memoryPercentLabel != null) {
                    memoryPercentLabel.setText(String.format("%.1f%%", memPct));
                }
            }
            // 磁盘使用率
            if (metrics.getDiskUsage() > 0) {
                diskProgress.setProgress(metrics.getDiskUsage() / 100.0);
                if (diskPercentLabel != null) {
                    diskPercentLabel.setText(String.format("%.1f%%", metrics.getDiskUsage()));
                }
            }
            netUpLabel.setText(metrics.getNetUp());
            netDownLabel.setText(metrics.getNetDown());
            // 显示负载平均值
            if (loadLabel != null) {
                loadLabel.setText(String.format("Load: %.2f, %.2f, %.2f",
                        metrics.getLoadAverage1(),
                        metrics.getLoadAverage5(),
                        metrics.getLoadAverage15()));
            }
            // 显示网络连接数
            if (connectionsLabel != null) {
                connectionsLabel.setText(String.format("ESTABLISHED: %d, LISTEN: %d", 
                        metrics.getEstablishedConnections(), metrics.getListeningConnections()));
            }
                
            // 更新系统信息标签
            if (uptimeLabel != null) {
                uptimeLabel.setText(metrics.getUptime());
            }
            if (cpuLabel != null) {
                cpuLabel.setText(String.format("%.1f%%", metrics.getCpuUser()));
            }
            if (memoryLabel != null) {
                long totalMem = metrics.getTotalMemory();
                double memoryUsagePercent = totalMem > 0 ? (double) metrics.getUsedMemory() / totalMem * 100 : 0;
                memoryLabel.setText(String.format("%d/%d (%.1f%%)", 
                        metrics.getUsedMemory(), totalMem, memoryUsagePercent));
            }
            if (swapLabel != null) {
                long totalSwap = metrics.getTotalSwap();
                double swapUsagePercent = totalSwap > 0 ? (double) metrics.getUsedSwap() / totalSwap * 100 : 0;
                swapLabel.setText(String.format("%d/%d (%.1f%%)", 
                        metrics.getUsedSwap(), totalSwap, swapUsagePercent));
            }
    
            // 更新历史图表数据
            updateCharts(metrics);
                
            // 检查告警状态
            checkAlerts(metrics);
        });
    }

    private void updateCharts(SystemMetrics metrics) {
        // 获取当前时间作为 X 轴值
        long currentTime = System.currentTimeMillis();
    
        // 更新 CPU 图表
        if (cpuSeries != null) {
            cpuSeries.getData().add(new XYChart.Data<>(currentTime, metrics.getCpuUser()));
            // 限制数据点数量，保持图表清晰
            if (cpuSeries.getData().size() > MAX_DATA_POINTS) {
                cpuSeries.getData().remove(0);
            }
        }
    
        // 更新内存图表
        if (memorySeries != null && metrics.getTotalMemory() > 0) {
            double memoryUsagePercent = (double) metrics.getUsedMemory() / metrics.getTotalMemory() * 100;
            memorySeries.getData().add(new XYChart.Data<>(currentTime, memoryUsagePercent));
            if (memorySeries.getData().size() > MAX_DATA_POINTS) {
                memorySeries.getData().remove(0);
            }
        }
    
        // 更新磁盘图表
        if (diskSeries != null) {
            diskSeries.getData().add(new XYChart.Data<>(currentTime, metrics.getDiskUsage()));
            if (diskSeries.getData().size() > MAX_DATA_POINTS) {
                diskSeries.getData().remove(0);
            }
        }
    
        // 更新网络图表
        if (networkUpSeries != null) {
            // 解析网络速度字符串并转换为数值
            double netUpValue = parseNetworkSpeed(metrics.getNetUp());
            networkUpSeries.getData().add(new XYChart.Data<>(currentTime, netUpValue));
            if (networkUpSeries.getData().size() > MAX_DATA_POINTS) {
                networkUpSeries.getData().remove(0);
            }
        }
        if (networkDownSeries != null) {
            // 解析网络速度字符串并转换为数值
            double netDownValue = parseNetworkSpeed(metrics.getNetDown());
            networkDownSeries.getData().add(new XYChart.Data<>(currentTime, netDownValue));
            if (networkDownSeries.getData().size() > MAX_DATA_POINTS) {
                networkDownSeries.getData().remove(0);
            }
        }
    
        // 动态调整 X 轴范围以显示最新的数据点
        if (cpuXAxis != null && cpuSeries != null && !cpuSeries.getData().isEmpty()) {
            long minTime = currentTime - CHART_TIME_RANGE; // 显示最近 1 分钟的数据
            cpuXAxis.setAutoRanging(false);
            cpuXAxis.setLowerBound(minTime);
            cpuXAxis.setUpperBound(currentTime + 5000); // 稍微超出当前时间
        }
        if (memoryXAxis != null && memorySeries != null && !memorySeries.getData().isEmpty()) {
            long minTime = currentTime - CHART_TIME_RANGE;
            memoryXAxis.setAutoRanging(false);
            memoryXAxis.setLowerBound(minTime);
            memoryXAxis.setUpperBound(currentTime + 5000);
        }
        if (diskXAxis != null && diskSeries != null && !diskSeries.getData().isEmpty()) {
            long minTime = currentTime - CHART_TIME_RANGE;
            diskXAxis.setAutoRanging(false);
            diskXAxis.setLowerBound(minTime);
            diskXAxis.setUpperBound(currentTime + 5000);
        }
        if (networkXAxis != null && networkUpSeries != null && !networkUpSeries.getData().isEmpty()) {
            long minTime = currentTime - CHART_TIME_RANGE;
            networkXAxis.setAutoRanging(false);
            networkXAxis.setLowerBound(minTime);
            networkXAxis.setUpperBound(currentTime + 5000);
        }
    }

    private double parseNetworkSpeed(String speedStr) {
        if (speedStr == null || speedStr.isEmpty()) {
            return 0.0;
        }
        try {
            // 从 "X KB/s" 或 "X MB/s" 等格式中提取数值
            String numPart = speedStr.replaceAll("[^0-9.]", "");
            return Double.parseDouble(numPart);
        } catch (NumberFormatException e) {
            log.warn("Could not parse network speed: {}", speedStr);
            return 0.0;
        }
    }

    /**
     * 停止所有监控器
     */
    public void stopAllMonitors() {
        for (SystemMonitor monitor : tabSystemMonitors.values()) {
            if (monitor != null) {
                monitor.stopMonitoring();
            }
        }
        tabSystemMonitors.clear();
        if (currentSystemMonitor != null) {
            currentSystemMonitor.stopMonitoring();
            currentSystemMonitor = null;
        }
    }
    
    /**
     * 获取当前系统监控器
     */
    public SystemMonitor getCurrentSystemMonitor() {
        return currentSystemMonitor;
    }

    /**
     * 检查指标并触发告警
     */
    private void checkAlerts(SystemMetrics metrics) {
        // CPU 告警检查
        checkCpuAlert(metrics.getCpuUser());
        
        // 内存告警检查
        long totalMem = metrics.getTotalMemory();
        double memoryUsagePercent = totalMem > 0 ? (double) metrics.getUsedMemory() / totalMem * 100 : 0;
        checkMemoryAlert(memoryUsagePercent);
        
        // 磁盘告警检查
        checkDiskAlert(metrics.getDiskUsage());
    }

    /**
     * CPU 告警检查
     */
    private void checkCpuAlert(double cpuUsage) {
        if (cpuUsage >= CPU_CRITICAL_THRESHOLD) {
            log.warn("⚠️ CPU 严重告警：{:.1f}% (阈值：{:.0f}%)", cpuUsage, CPU_CRITICAL_THRESHOLD);
            updateProgressBarStyle(cpuProgress, "critical");
        } else if (cpuUsage >= CPU_WARNING_THRESHOLD) {
            log.warn("⚠️ CPU 警告：{:.1f}% (阈值：{:.0f}%)", cpuUsage, CPU_WARNING_THRESHOLD);
            updateProgressBarStyle(cpuProgress, "warning");
        } else {
            updateProgressBarStyle(cpuProgress, "normal");
        }
    }

    /**
     * 内存告警检查
     */
    private void checkMemoryAlert(double memoryUsage) {
        if (memoryUsage >= MEMORY_CRITICAL_THRESHOLD) {
            log.warn("⚠️ 内存严重告警：{:.1f}% (阈值：{:.0f}%)", memoryUsage, MEMORY_CRITICAL_THRESHOLD);
            updateProgressBarStyle(memoryProgress, "critical");
        } else if (memoryUsage >= MEMORY_WARNING_THRESHOLD) {
            log.warn("⚠️ 内存警告：{:.1f}% (阈值：{:.0f}%)", memoryUsage, MEMORY_WARNING_THRESHOLD);
            updateProgressBarStyle(memoryProgress, "warning");
        } else {
            updateProgressBarStyle(memoryProgress, "normal");
        }
    }

    /**
     * 磁盘告警检查
     */
    private void checkDiskAlert(double diskUsage) {
        if (diskUsage >= DISK_CRITICAL_THRESHOLD) {
            log.warn("⚠️ 磁盘严重告警：{:.1f}% (阈值：{:.0f}%)", diskUsage, DISK_CRITICAL_THRESHOLD);
            updateProgressBarStyle(diskProgress, "critical");
        } else if (diskUsage >= DISK_WARNING_THRESHOLD) {
            log.warn("⚠️ 磁盘警告：{:.1f}% (阈值：{:.0f}%)", diskUsage, DISK_WARNING_THRESHOLD);
            updateProgressBarStyle(diskProgress, "warning");
        } else {
            updateProgressBarStyle(diskProgress, "normal");
        }
    }

    /**
     * 更新进度条样式
     */
    private void updateProgressBarStyle(ProgressBar progressBar, String status) {
        if (progressBar == null) return;
        
        // 移除所有状态样式类
        progressBar.getStyleClass().removeAll("progress-normal", "progress-warning", "progress-critical");
        
        // 添加新的状态样式类
        switch (status) {
            case "critical":
                progressBar.getStyleClass().add("progress-critical");
                break;
            case "warning":
                progressBar.getStyleClass().add("progress-warning");
                break;
            default:
                progressBar.getStyleClass().add("progress-normal");
                break;
        }
    }
}
