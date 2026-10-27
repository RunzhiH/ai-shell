/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程池管理器
 * 
 * 提供统一的线程池管理，避免创建过多线程池实例，优化系统资源使用。
 * 支持不同类型的线程池配置，包括：
 * - IO密集型任务线程池
 * - CPU密集型任务线程池  
 * - 定时调度线程池
 * - 缓存线程池
 * 
 * 特性：
 * - 统一管理所有线程池生命周期
 * - 合理的默认配置
 * - 优雅关闭机制
 * - 监控和统计功能
 * 
 * @author AIShell Team
 * @version 1.0
 * @since 2024
 */
@Slf4j
public class ThreadPoolManager {
    
    // 单例实例
    private static volatile ThreadPoolManager instance;
    
    // 不同用途的线程池
    private final ExecutorService ioThreadPool;      // IO密集型任务
    private final ExecutorService cpuThreadPool;     // CPU密集型任务
    private final ScheduledExecutorService scheduledThreadPool; // 定时调度任务
    private final ExecutorService cachedThreadPool;  // 缓存线程池（用于短期任务）
    
    // 线程工厂，用于创建带有有意义名称的线程
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        
        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-thread-" + threadNumber.getAndIncrement());
            t.setDaemon(false); // 非守护线程，确保应用正常关闭
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
    
    private ThreadPoolManager() {
        log.info("Initializing ThreadPoolManager...");
        
        // 获取系统核心数，用于合理配置线程池大小
        int coreCount = Runtime.getRuntime().availableProcessors();
        log.info("Available processors: {}", coreCount);
        
        // IO密集型线程池 - 通常是CPU核心数的2倍
        int ioThreadCount = Math.max(coreCount * 2, 8);
        this.ioThreadPool = new ThreadPoolExecutor(
            ioThreadCount,           // 核心线程数
            ioThreadCount * 2,       // 最大线程数
            60L,                     // 空闲线程存活时间
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000), // 有界队列，防止内存溢出
            new NamedThreadFactory("IO-Pool"),
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者运行
        );
        
        // CPU密集型线程池 - 通常等于CPU核心数
        int cpuThreadCount = coreCount;
        this.cpuThreadPool = new ThreadPoolExecutor(
            cpuThreadCount,
            cpuThreadCount,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new NamedThreadFactory("CPU-Pool"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 定时调度线程池 - 用于周期性任务
        this.scheduledThreadPool = new ScheduledThreadPoolExecutor(
            Math.max(coreCount / 2, 2), // 调度线程数
            new NamedThreadFactory("Scheduled-Pool"),
            new ThreadPoolExecutor.AbortPolicy() // 调度任务拒绝时抛出异常
        );
        
        // 缓存线程池 - 用于短期、大量的小任务
        this.cachedThreadPool = Executors.newCachedThreadPool(new NamedThreadFactory("Cached-Pool"));
        
        log.info("ThreadPoolManager initialized successfully");
        log.info("IO Thread Pool: {}-{} threads", ioThreadCount, ioThreadCount * 2);
        log.info("CPU Thread Pool: {} threads", cpuThreadCount);
        log.info("Scheduled Thread Pool: {} threads", Math.max(coreCount / 2, 2));
    }
    
    public static ThreadPoolManager getInstance() {
        if (instance == null) {
            synchronized (ThreadPoolManager.class) {
                if (instance == null) {
                    instance = new ThreadPoolManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 获取IO密集型任务线程池
     * 适用于文件读写、网络请求、数据库操作等IO密集型任务
     */
    public ExecutorService getIoThreadPool() {
        return ioThreadPool;
    }
    
    /**
     * 获取CPU密集型任务线程池
     * 适用于计算密集型任务，如数据处理、算法计算等
     */
    public ExecutorService getCpuThreadPool() {
        return cpuThreadPool;
    }
    
    /**
     * 获取定时调度线程池
     * 适用于需要定期执行的任务，如监控、定时清理等
     */
    public ScheduledExecutorService getScheduledThreadPool() {
        return scheduledThreadPool;
    }
    
    /**
     * 获取缓存线程池
     * 适用于短期、大量的小任务
     */
    public ExecutorService getCachedThreadPool() {
        return cachedThreadPool;
    }
    
    /**
     * 获取或创建指定名称的线程池
     * @param poolName 线程池名称
     * @param corePoolSize 核心线程数
     * @param maximumPoolSize 最大线程数
     * @param keepAliveTime 空闲线程存活时间（秒）
     * @return 线程池实例
     */
    public ScheduledExecutorService getOrCreateThreadPool(String poolName, int corePoolSize, 
                                                         int maximumPoolSize, long keepAliveTime) {
        // 对于健康检查这类专用线程池，使用调度线程池
        return new ScheduledThreadPoolExecutor(
            corePoolSize,
            new NamedThreadFactory(poolName),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }
    
    /**
     * 提交IO密集型任务
     */
    public <T> Future<T> submitIoTask(Callable<T> task) {
        return ioThreadPool.submit(task);
    }
    
    /**
     * 提交CPU密集型任务
     */
    public <T> Future<T> submitCpuTask(Callable<T> task) {
        return cpuThreadPool.submit(task);
    }
    
    /**
     * 提交定时任务
     */
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return scheduledThreadPool.schedule(command, delay, unit);
    }
    
    /**
     * 提交周期性任务
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, 
                                                  long initialDelay, 
                                                  long period, 
                                                  TimeUnit unit) {
        return scheduledThreadPool.scheduleAtFixedRate(command, initialDelay, period, unit);
    }
    
    /**
     * 获取线程池统计信息
     */
    public ThreadPoolStats getThreadPoolStats() {
        ThreadPoolStats stats = new ThreadPoolStats();
        
        if (ioThreadPool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) ioThreadPool;
            stats.ioActiveThreads = executor.getActiveCount();
            stats.ioPoolSize = executor.getPoolSize();
            stats.ioCompletedTasks = executor.getCompletedTaskCount();
            stats.ioQueuedTasks = executor.getQueue().size();
        }
        
        if (cpuThreadPool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) cpuThreadPool;
            stats.cpuActiveThreads = executor.getActiveCount();
            stats.cpuPoolSize = executor.getPoolSize();
            stats.cpuCompletedTasks = executor.getCompletedTaskCount();
            stats.cpuQueuedTasks = executor.getQueue().size();
        }
        
        if (scheduledThreadPool instanceof ScheduledThreadPoolExecutor) {
            ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) scheduledThreadPool;
            stats.scheduledActiveThreads = executor.getActiveCount();
            stats.scheduledPoolSize = executor.getPoolSize();
            stats.scheduledCompletedTasks = executor.getCompletedTaskCount();
        }
        
        return stats;
    }
    
    /**
     * 优雅关闭所有线程池
     */
    public void shutdown() {
        log.info("Shutting down ThreadPoolManager...");
        
        // 关闭定时调度线程池
        scheduledThreadPool.shutdown();
        try {
            if (!scheduledThreadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Scheduled thread pool did not terminate in time, forcing shutdown");
                scheduledThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for scheduled thread pool shutdown", e);
            scheduledThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭IO线程池
        ioThreadPool.shutdown();
        try {
            if (!ioThreadPool.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("IO thread pool did not terminate in time, forcing shutdown");
                ioThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for IO thread pool shutdown", e);
            ioThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭CPU线程池
        cpuThreadPool.shutdown();
        try {
            if (!cpuThreadPool.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("CPU thread pool did not terminate in time, forcing shutdown");
                cpuThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for CPU thread pool shutdown", e);
            cpuThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭缓存线程池
        cachedThreadPool.shutdown();
        try {
            if (!cachedThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Cached thread pool did not terminate in time, forcing shutdown");
                cachedThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for cached thread pool shutdown", e);
            cachedThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("ThreadPoolManager shutdown completed");
    }
    
    /**
     * 强制关闭所有线程池（不等待任务完成）
     */
    public void shutdownNow() {
        log.info("Forcefully shutting down ThreadPoolManager...");
        scheduledThreadPool.shutdownNow();
        ioThreadPool.shutdownNow();
        cpuThreadPool.shutdownNow();
        cachedThreadPool.shutdownNow();
        log.info("ThreadPoolManager forcefully shutdown completed");
    }
    
    /**
     * 线程池统计信息类
     */
    public static class ThreadPoolStats {
        public int ioActiveThreads;
        public int ioPoolSize;
        public long ioCompletedTasks;
        public int ioQueuedTasks;
        
        public int cpuActiveThreads;
        public int cpuPoolSize;
        public long cpuCompletedTasks;
        public int cpuQueuedTasks;
        
        public int scheduledActiveThreads;
        public int scheduledPoolSize;
        public long scheduledCompletedTasks;
        
        @Override
        public String toString() {
            return String.format(
                "ThreadPoolStats{\n" +
                "  IO Pool - Active: %d, Size: %d, Completed: %d, Queued: %d\n" +
                "  CPU Pool - Active: %d, Size: %d, Completed: %d, Queued: %d\n" +
                "  Scheduled Pool - Active: %d, Size: %d, Completed: %d\n" +
                "}",
                ioActiveThreads, ioPoolSize, ioCompletedTasks, ioQueuedTasks,
                cpuActiveThreads, cpuPoolSize, cpuCompletedTasks, cpuQueuedTasks,
                scheduledActiveThreads, scheduledPoolSize, scheduledCompletedTasks
            );
        }
    }
}