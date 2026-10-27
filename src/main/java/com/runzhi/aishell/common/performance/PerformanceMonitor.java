/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.performance;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import com.runzhi.aishell.common.util.ThreadPoolManager;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

/**
 * 性能监控器
 * 监控应用程序的CPU、内存、GC等性能指标
 */
@Slf4j
public class PerformanceMonitor {
    
    private static volatile PerformanceMonitor instance;
    private final ScheduledExecutorService scheduler = ThreadPoolManager.getInstance().getScheduledThreadPool();
    
    // 性能指标属性
    private final DoubleProperty cpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty memoryUsagePercent = new SimpleDoubleProperty(0.0);
    private final LongProperty usedMemory = new SimpleLongProperty(0L);
    private final LongProperty totalMemory = new SimpleLongProperty(0L);
    private final LongProperty maxMemory = new SimpleLongProperty(0L);
    private final LongProperty gcCount = new SimpleLongProperty(0L);
    private final LongProperty gcTime = new SimpleLongProperty(0L);
    
    // 历史数据
    private final ObservableList<PerformanceDataPoint> performanceHistory = 
        FXCollections.observableArrayList();
    
    // 监控状态
    private volatile boolean monitoring = false;
    private ScheduledFuture<?> monitoringTask;
    private final AtomicLong lastGcCount = new AtomicLong(0);
    private final AtomicLong lastGcTime = new AtomicLong(0);
    
    private MemoryMXBean memoryBean;
    
    private PerformanceMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }
    
    public static synchronized PerformanceMonitor getInstance() {
        if (instance == null) {
            instance = new PerformanceMonitor();
        }
        return instance;
    }
    
    /**
     * 开始性能监控
     */
    public void startMonitoring() {
        if (monitoring) {
            return;
        }

        monitoring = true;

        // 每秒更新一次性能数据
        monitoringTask = scheduler.scheduleAtFixedRate(this::updatePerformanceData, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 停止性能监控
     */
    public void stopMonitoring() {
        monitoring = false;
        if (monitoringTask != null) {
            monitoringTask.cancel(false);
            monitoringTask = null;
        }
    }
    
    /**
     * 更新性能数据
     */
    private void updatePerformanceData() {
        if (!monitoring) {
            return;
        }
        
        try {
            // 获取内存使用情况
            MemoryUsage heapMemoryUsage = memoryBean.getHeapMemoryUsage();
            long used = heapMemoryUsage.getUsed();
            long committed = heapMemoryUsage.getCommitted();
            long max = heapMemoryUsage.getMax();
            
            // 更新内存指标
            Platform.runLater(() -> {
                usedMemory.set(used);
                totalMemory.set(committed);
                maxMemory.set(max);
                
                if (committed > 0) {
                    memoryUsagePercent.set((double) used / committed * 100);
                }
            });
            
            // 计算CPU使用率（简化版实现）
            updateCpuUsage();
            
            // 记录性能数据点
            recordPerformanceDataPoint(used, committed, max);
            
        } catch (Exception e) {
            log.error("Failed to update performance data", e);
        }
    }
    
    /**
     * 更新CPU使用率（本 JVM 进程占用）
     *
     * <p>com.sun.management.OperatingSystemMXBean 属于 jdk.management 内部 API，
     * 本项目模块未 requires 它，因此改用系统负载均值作为可用性指标。
     * 系统负载均值反映整机繁忙程度，作为本地性能监控器的近似指标足够。</p>
     */
    private void updateCpuUsage() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            double loadAverage = osBean.getSystemLoadAverage();
            // getSystemLoadAverage 在不可用时返回 -1；可用处理器数用于归一化为百分比
            int processors = osBean.getAvailableProcessors();
            double value = (loadAverage >= 0 && processors > 0)
                    ? Math.min(100.0, (loadAverage / processors) * 100)
                    : 0.0;
            Platform.runLater(() -> cpuUsage.set(value));
        } catch (Exception e) {
            // 取不到指标时保持上一次的值，不抛出破坏监控循环
            log.debug("Unable to read system load average: {}", e.getMessage());
        }
    }
    
    /**
     * 记录性能数据点
     */
    private void recordPerformanceDataPoint(long usedMemory, long totalMemory, long maxMemory) {
        PerformanceDataPoint dataPoint = new PerformanceDataPoint(
            System.currentTimeMillis(),
            cpuUsage.get(),
            usedMemory,
            totalMemory,
            maxMemory,
            gcCount.get(),
            gcTime.get()
        );
        
        // 限制历史数据大小，最多保留1000个数据点
        if (performanceHistory.size() >= 1000) {
            performanceHistory.remove(0);
        }
        
        performanceHistory.add(dataPoint);
    }
    
    /**
     * 获取CPU使用率
     */
    public DoubleProperty cpuUsageProperty() {
        return cpuUsage;
    }
    
    /**
     * 获取内存使用百分比
     */
    public DoubleProperty memoryUsagePercentProperty() {
        return memoryUsagePercent;
    }
    
    /**
     * 获取当前CPU使用率
     */
    public double getCpuUsage() {
        return cpuUsage.get();
    }
    
    /**
     * 获取当前内存使用百分比
     */
    public double getMemoryUsagePercent() {
        return memoryUsagePercent.get();
    }
    
    /**
     * 获取已使用内存
     */
    public LongProperty usedMemoryProperty() {
        return usedMemory;
    }
    
    /**
     * 获取总内存
     */
    public LongProperty totalMemoryProperty() {
        return totalMemory;
    }
    
    /**
     * 获取最大内存
     */
    public LongProperty maxMemoryProperty() {
        return maxMemory;
    }
    
    /**
     * 获取GC次数
     */
    public LongProperty gcCountProperty() {
        return gcCount;
    }
    
    /**
     * 获取GC时间
     */
    public LongProperty gcTimeProperty() {
        return gcTime;
    }
    
    /**
     * 获取性能历史数据
     */
    public ObservableList<PerformanceDataPoint> getPerformanceHistory() {
        return performanceHistory;
    }
    
    /**
     * 获取当前性能摘要
     */
    public String getPerformanceSummary() {
        return String.format(
            "CPU: %.2f%% | 内存: %s/%s (%.2f%%) | GC: %d次, %dms",
            cpuUsage.get(),
            formatBytes(usedMemory.get()),
            formatBytes(totalMemory.get()),
            memoryUsagePercent.get(),
            gcCount.get(),
            gcTime.get()
        );
    }
    
    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * 获取性能监控状态
     */
    public boolean isMonitoring() {
        return monitoring;
    }
    
    /**
     * 清除性能历史数据
     */
    public void clearHistory() {
        performanceHistory.clear();
    }
    
    /**
     * 性能数据点类
     */
    public static class PerformanceDataPoint {
        private final long timestamp;
        private final double cpuUsage;
        private final long usedMemory;
        private final long totalMemory;
        private final long maxMemory;
        private final long gcCount;
        private final long gcTime;
        
        public PerformanceDataPoint(long timestamp, double cpuUsage, long usedMemory, 
                                   long totalMemory, long maxMemory, long gcCount, long gcTime) {
            this.timestamp = timestamp;
            this.cpuUsage = cpuUsage;
            this.usedMemory = usedMemory;
            this.totalMemory = totalMemory;
            this.maxMemory = maxMemory;
            this.gcCount = gcCount;
            this.gcTime = gcTime;
        }
        
        public long getTimestamp() { return timestamp; }
        public double getCpuUsage() { return cpuUsage; }
        public long getUsedMemory() { return usedMemory; }
        public long getTotalMemory() { return totalMemory; }
        public long getMaxMemory() { return maxMemory; }
        public long getGcCount() { return gcCount; }
        public long getGcTime() { return gcTime; }
    }
    
    /**
     * 关闭性能监控器
     *
     * <p>注意：scheduler 来自全局共享的 {@link ThreadPoolManager}，这里只停止本监控器的周期任务，
     * 不能关闭调度器本身，否则会连带停掉整个应用的所有定时任务。</p>
     */
    public void shutdown() {
        stopMonitoring();
    }
}