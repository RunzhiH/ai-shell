/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.performance;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import com.runzhi.aishell.common.util.ThreadPoolManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * 资源优化器
 * 优化应用程序的内存和CPU使用
 */
@Slf4j
public class ResourceOptimizer {

    private static volatile ResourceOptimizer instance;
    private ScheduledExecutorService optimizerScheduler; // 懒加载
    private ScheduledFuture<?> optimizationTask;
    private final AtomicBoolean optimizationEnabled = new AtomicBoolean(true);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final List<WeakReference<Object>> monitoredResources = new ArrayList<>();
    private volatile boolean optimizing = false;
    private static final int CONNECTION_THRESHOLD = 10;

    private ResourceOptimizer() {
        // 构造函数中不初始化调度器，避免阻塞
    }

    /**
     * 获取实例 - 双重检查锁定
     */
    public static ResourceOptimizer getInstance() {
        if (instance == null) {
            synchronized (ResourceOptimizer.class) {
                if (instance == null) {
                    instance = new ResourceOptimizer();
                }
            }
        }
        return instance;
    }

    /**
     * 懒加载获取调度器
     */
    private ScheduledExecutorService getScheduler() {
        if (optimizerScheduler == null) {
            synchronized (this) {
                if (optimizerScheduler == null) {
                    try {
                        optimizerScheduler = ThreadPoolManager.getInstance().getScheduledThreadPool();
                        log.debug("ResourceOptimizer scheduler initialized lazily");
                    } catch (Exception e) {
                        log.error("Failed to initialize scheduler", e);
                        throw new RuntimeException("Scheduler initialization failed", e);
                    }
                }
            }
        }
        return optimizerScheduler;
    }

    /**
     * 启动资源优化
     */
    public void startOptimization() {
        if (optimizing) {
            return;
        }

        optimizing = true;

        try {
            // 每 30 秒执行一次资源优化
            ScheduledExecutorService scheduler = getScheduler();
            optimizationTask = scheduler.scheduleAtFixedRate(this::performOptimization, 30, 30, TimeUnit.SECONDS);
            log.info("Resource optimization started with 30s interval");
        } catch (Exception e) {
            log.error("Failed to start resource optimization", e);
            optimizing = false;
        }
    }

    /**
     * 停止资源优化
     */
    public void stopOptimization() {
        optimizing = false;
        if (optimizationTask != null) {
            optimizationTask.cancel(false);
            optimizationTask = null;
        }
    }

    /**
     * 关闭资源优化器
     *
     * <p>注意：optimizerScheduler 取自全局共享的 {@link ThreadPoolManager}，不能在此关闭它，
     * 否则会连带停掉整个应用的所有定时任务。</p>
     */
    public void shutdown() {
        stopOptimization();
    }

    /**
     * 执行资源优化
     */
    private void performOptimization() {
        if (!optimizationEnabled.get()) {
            return;
        }

        try {
            // 执行内存优化
            performMemoryOptimization();

            // 执行连接优化
            performConnectionOptimization();

            // 执行缓存清理
            performCacheCleanup();

            // 执行垃圾回收建议
            suggestGarbageCollection();

        } catch (Exception e) {
            log.error("资源优化过程中发生错误", e);
        }
    }

    /**
     * 执行内存优化
     */
    private void performMemoryOptimization() {
        // 清理弱引用列表
        monitoredResources.removeIf(ref -> ref.get() == null);

        // 如果内存使用过高，尝试减少缓存大小
        PerformanceMonitor monitor = PerformanceMonitor.getInstance();
        if (monitor.memoryUsagePercentProperty().get() > 80) {
            // 在JavaFX应用线程中执行UI相关的优化
            javafx.application.Platform.runLater(() -> {
                // 清理UI组件的临时数据
                clearTempUIData();
            });
        }
    }

    /**
     * 执行连接优化
     */
    private void performConnectionOptimization() {
        int currentConnections = activeConnections.get();

        // 如果连接数过多，考虑清理闲置连接
        if (currentConnections > CONNECTION_THRESHOLD) {
            log.warn("检测到过多活动连接: {}，可能需要清理", currentConnections);
            // 可以在这里添加连接清理逻辑
        }
    }

    /**
     * 执行缓存清理
     */
    private void performCacheCleanup() {
        // 这里可以实现具体的缓存清理逻辑
        // 例如清理过期的命令历史、AI响应缓存等
        clearExpiredCaches();
    }

    /**
     * 清理过期缓存
     */
    private void clearExpiredCaches() {
        // 示例：清理AI增强工具中的过期缓存
        // 实际实现会根据具体使用的缓存系统而定

    }

    /**
     * 清理临时UI数据
     */
    private void clearTempUIData() {
        // 清理临时UI数据

    }

    /**
     * 建议垃圾回收
     */
    private void suggestGarbageCollection() {
        // 如果内存使用率超过阈值，建议垃圾回收
        PerformanceMonitor monitor = PerformanceMonitor.getInstance();

        if (monitor.memoryUsagePercentProperty().get() > 85) {
            System.gc(); // 建议JVM进行垃圾回收

        }
    }

    /**
     * 监控资源
     */
    public void monitorResource(Object resource) {
        monitoredResources.add(new WeakReference<>(resource));
    }

    /**
     * 设置活跃连接数
     */
    public void setActiveConnections(int count) {
        activeConnections.set(count);
    }

    /**
     * 增加活跃连接数
     */
    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
    }

    /**
     * 减少活跃连接数
     */
    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    /**
     * 获取活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /**
     * 设置优化启用状态
     */
    public void setOptimizationEnabled(boolean enabled) {
        optimizationEnabled.set(enabled);
    }

    /**
     * 获取优化启用状态
     */
    public boolean isOptimizationEnabled() {
        return optimizationEnabled.get();
    }

    /**
     * 获取资源优化状态
     */
    public boolean isOptimizing() {
        return optimizing;
    }

    /**
     * 获取资源使用摘要
     */
    public String getResourceUsageSummary() {
        PerformanceMonitor monitor = PerformanceMonitor.getInstance();
        return String.format(
                "优化状态: %s | 活跃连接: %d | 内存使用: %.2f%%",
                isOptimizationEnabled() ? "启用" : "禁用",
                getActiveConnections(),
                monitor.memoryUsagePercentProperty().get()
        );
    }

    /**
     * 强制执行一次优化
     */
    public void forceOptimize() {
        performOptimization();
    }
}