/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.ai;

import com.runzhi.aishell.ai.AIService;
import com.runzhi.aishell.ai.impl.AIServiceImpl;

import com.runzhi.aishell.common.di.ApplicationContext;
import com.runzhi.aishell.common.di.ServiceRegistry;
import com.runzhi.aishell.common.performance.PerformanceMonitor;
import com.runzhi.aishell.common.performance.ResourceOptimizer;
import com.runzhi.aishell.common.util.ThreadPoolManager;
import com.runzhi.aishell.monitor.DataSourceProvider;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.service.impl.TerminalCommandServiceImpl;
import com.runzhi.aishell.service.impl.TerminalConnectionServiceImpl;
import com.runzhi.aishell.service.impl.TerminalTabServiceImpl;
import com.runzhi.aishell.service.impl.TerminalUIServiceImpl;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务生命周期管理器 — 负责所有服务的三阶段并行初始化和关闭。
 *
 * <p>从 AIShellModuleManager 中提取，仅关注"怎么初始化/关闭"，
 * 不持有服务引用的获取逻辑（由 AIShellModuleManager 负责）。
 */
@Slf4j
class ServiceLifecycleManager {

    private final ExecutorService executorService = ThreadPoolManager.getInstance().getCachedThreadPool();

    // ==================== 初始化 ====================

    void initializeAll(ServiceHolder holder, ServiceRegistry registry) {
        log.info("🚀 Initializing AI Shell modules...");
        long startTime = System.currentTimeMillis();

        try {
            ApplicationContext.getInstance().initialize();
            initCoreServices(holder, registry);
            initTerminalServices(holder, registry);
            initAuxiliaryServices(holder);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("🎉 All modules initialized successfully in {}ms", elapsed);
        } catch (TimeoutException e) {
            log.error("❌ Initialization timeout after 120 seconds", e);
            throw new RuntimeException("Initialization timeout", e);
        } catch (Exception e) {
            log.error("❌ Initialization failed", e);
            throw new RuntimeException("Initialization failed", e);
        }
    }

    private void initCoreServices(ServiceHolder holder, ServiceRegistry registry) throws Exception {
        log.info("Phase 1: Initializing core services (parallel)...");
        CompletableFuture<Void> sshInit = initServiceAsync("SSH Service", () -> {
            holder.sshService = new SSHServiceImpl();
            registry.register(SSHService.class, holder.sshService);
        });
        CompletableFuture<Void> aiInit = initServiceAsync("AI Service", () -> {
            holder.aiService = new AIServiceImpl();
            registry.register(AIService.class, holder.aiService);
        });
        CompletableFuture.allOf(sshInit, aiInit).get(120, TimeUnit.SECONDS);
        log.info("✅ Core services initialized.");
    }

    private void initTerminalServices(ServiceHolder holder, ServiceRegistry registry) {
        if (holder.sshService == null) {
            throw new IllegalStateException("SSH Service must be initialized before terminal services");
        }
        log.info("Phase 2: Initializing terminal services (depends on SSH)...");
        log.debug("Initializing Terminal Services with dependency injection...");

        if (holder.terminalTabService == null) {
            holder.terminalTabService = new TerminalTabServiceImpl();
            registry.register(com.runzhi.aishell.service.TerminalTabService.class, holder.terminalTabService);
        }
        if (holder.terminalCommandService == null) {
            holder.terminalCommandService = new TerminalCommandServiceImpl();
            registry.register(com.runzhi.aishell.service.TerminalCommandService.class, holder.terminalCommandService);
        }
        if (holder.terminalConnectionService == null) {
            holder.terminalConnectionService = new TerminalConnectionServiceImpl(holder.sshService);
            registry.register(com.runzhi.aishell.service.TerminalConnectionService.class, holder.terminalConnectionService);
        }
        if (holder.terminalUIService == null) {
            holder.terminalUIService = new TerminalUIServiceImpl();
            registry.register(com.runzhi.aishell.service.TerminalUIService.class, holder.terminalUIService);
        }
        if (holder.terminalConnectionService != null && holder.terminalUIService != null) {
            holder.terminalConnectionService.setTerminalUIService(holder.terminalUIService);
        }
        log.info("✅ Terminal services initialized.");
    }

    private void initAuxiliaryServices(ServiceHolder holder) {
        log.info("Phase 3: Initializing auxiliary services (parallel with fault tolerance)...");
        try {
            CompletableFuture<Void> monitorInit = initServiceAsync("System Monitor",
                    () -> holder.systemMonitor = new SystemMonitor(new DummyDataSourceProvider()));
            CompletableFuture<Void> perfMonitorInit = initServiceAsync("Performance Monitor",
                    () -> holder.performanceMonitor = PerformanceMonitor.getInstance());
            CompletableFuture<Void> optimizerInit = initServiceAsync("Resource Optimizer", () -> {
                if (holder.performanceMonitor == null) {
                    try { perfMonitorInit.get(10, TimeUnit.SECONDS); }
                    catch (Exception e) { log.error("Failed to wait for PerformanceMonitor", e); }
                }
                holder.resourceOptimizer = ResourceOptimizer.getInstance();
                // startOptimization 只会向调度器注册定时任务，不触碰 JavaFX UI，
                // 因此无需 Platform.runLater —— 在启动阶段让 worker 线程等待 FX 线程
                // 正是历史上初始化死锁（启动超时 180s）的成因。
                try {
                    holder.resourceOptimizer.startOptimization();
                    log.info("Resource optimization started in background");
                } catch (Exception e) {
                    log.error("Failed to start resource optimization", e);
                }
            });
            CompletableFuture.allOf(monitorInit, perfMonitorInit, optimizerInit)
                    .get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("⚠️ Auxiliary services initialization timeout, continuing with partial services");
        } catch (Exception e) {
            log.warn("⚠️ Auxiliary services initialization failed, continuing without some services");
        }
    }

    // ==================== 关闭 ====================

    void shutdownAll(ServiceHolder holder) {
        log.info("🛑 Shutting down AI Shell modules...");
        long startTime = System.currentTimeMillis();
        try {
            CompletableFuture<Void> monitorShutdown = shutdownServiceAsync("System Monitor",
                    () -> { if (holder.systemMonitor != null) holder.systemMonitor.stopMonitoring(); });
            CompletableFuture<Void> perfShutdown = shutdownServiceAsync("Performance Monitor",
                    () -> { if (holder.performanceMonitor != null) holder.performanceMonitor.shutdown(); });
            CompletableFuture<Void> optimizerShutdown = shutdownServiceAsync("Resource Optimizer",
                    () -> { if (holder.resourceOptimizer != null) holder.resourceOptimizer.shutdown(); });
            CompletableFuture.allOf(monitorShutdown, perfShutdown, optimizerShutdown)
                    .get(15, TimeUnit.SECONDS);

            // 断开所有 SSH 会话与文件传输通道。读取线程均为守护线程，JVM 本身能退出，
            // 但不主动 disconnect 会让远程 sshd 等到 TCP 超时才回收会话。
            if (holder.sshService != null) {
                try {
                    holder.sshService.disconnect();
                    log.info("✓ SSH service disconnected");
                } catch (Exception e) {
                    log.error("Failed to disconnect SSH service cleanly", e);
                }
            }

            executorService.shutdown();
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("🎉 All modules shut down in {}ms", elapsed);
        } catch (TimeoutException e) {
            log.error("❌ Shutdown timeout", e);
        } catch (InterruptedException e) {
            log.error("❌ Shutdown interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("❌ Shutdown failed", e);
        }
    }

    // ==================== 工具方法 ====================

    private CompletableFuture<Void> initServiceAsync(String name, Runnable initializer) {
        return CompletableFuture.runAsync(() -> {
            long start = System.currentTimeMillis();
            log.debug("Starting {} initialization...", name);
            try {
                initializer.run();
                log.debug("✓ {} initialized in {}ms", name, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("Failed to initialize {}", name, e);
                throw e;
            }
        }, executorService);
    }

    private CompletableFuture<Void> shutdownServiceAsync(String name, Runnable action) {
        return CompletableFuture.runAsync(() -> {
            log.debug("Stopping {}...", name);
            try {
                action.run();
                log.debug("✓ {} stopped", name);
            } catch (Exception e) {
                log.error("Failed to stop {}", name, e);
            }
        }, executorService);
    }

    // ==================== 内部类 ====================

    /**
     * 服务持有者 — 所有可变的服务引用集中存储，避免 ServiceLifecycleManager 和
     * AIShellModuleManager 之间的循环依赖。
     */
    static class ServiceHolder {
        SSHService sshService;
        AIService aiService;
        SystemMonitor systemMonitor;
        TerminalTabServiceImpl terminalTabService;
        TerminalCommandServiceImpl terminalCommandService;
        TerminalConnectionServiceImpl terminalConnectionService;
        TerminalUIServiceImpl terminalUIService;

        PerformanceMonitor performanceMonitor;
        ResourceOptimizer resourceOptimizer;
    }

    private static class DummyDataSourceProvider implements DataSourceProvider {
        @Override public String executeCommand(String command) throws Exception {
            return "uname -a".equals(command) ? "dummy system info" : "No connection established";
        }
        @Override public boolean isConnected() { return false; }
    }
}
