/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.ai;


import com.runzhi.aishell.common.di.ServiceRegistry;
import com.runzhi.aishell.common.manager.NavigationManager;
import com.runzhi.aishell.common.performance.PerformanceMonitor;
import com.runzhi.aishell.common.performance.ResourceOptimizer;
import com.runzhi.aishell.common.util.ThreadPoolManager;
import com.runzhi.aishell.controller.FileManagerPanelController;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.service.TerminalCommandService;
import com.runzhi.aishell.service.TerminalConnectionService;
import com.runzhi.aishell.service.TerminalTabService;
import com.runzhi.aishell.service.TerminalUIService;
import com.runzhi.aishell.ssh.SSHService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AIShell 模块管理器 — 服务获取入口 + 生命周期协调。
 *
 * <p>初始化/关闭逻辑委托给 {@link ServiceLifecycleManager}；
 * 页面导航委托给 {@link NavigationManager}；
 * 参数管理委托给 {@link ParameterManager}；
 * 连接配置委托给 {@link ConfigManager}。
 */
@Slf4j
public class AIShellModuleManager implements ModuleManager {
    private static volatile AIShellModuleManager instance;
    private static volatile boolean initialized = false;

    // ==================== 生命周期管理 ====================
    private final ServiceLifecycleManager lifecycleManager = new ServiceLifecycleManager();
    private final ServiceLifecycleManager.ServiceHolder holder = new ServiceLifecycleManager.ServiceHolder();

    // ==================== 服务注册器 ====================
    @Getter
    private final ServiceRegistry serviceRegistry = new ServiceRegistry();

    // ==================== 导航管理器 ====================
    @Getter
    private final NavigationManager navigationManager = new NavigationManager();

    // ==================== 文件管理器映射 ====================
    private final Map<String, FileManagerPanelController> fileManagerControllers = new HashMap<>();
    private String stylesheetPath = "/com/runzhi/aishell/style.css";

    private AIShellModuleManager() {
    }

    public static AIShellModuleManager getInstance() {
        if (instance == null) {
            synchronized (AIShellModuleManager.class) {
                if (instance == null) {
                    AIShellModuleManager newInstance = new AIShellModuleManager();
                    instance = newInstance;
                    try {
                        newInstance.initialize();
                        initialized = true;
                    } catch (Exception e) {
                        log.error("Failed to initialize AIShellModuleManager", e);
                        initialized = false;
                        instance = null;
                        throw e;
                    }
                }
            }
        }
        return instance;
    }

    // ==================== 生命周期（委托） ====================

    @Override
    public void initialize() {
        lifecycleManager.initializeAll(holder, serviceRegistry);
    }

    @Override
    public void shutdown() {
        lifecycleManager.shutdownAll(holder);
    }

    @Override
    public void reload() {
        shutdown();
        initialize();
    }

    public CompletableFuture<Void> reloadAsync() {
        return CompletableFuture.runAsync(this::reload, ThreadPoolManager.getInstance().getCachedThreadPool());
    }

    // ==================== 服务获取器 ====================

    @Override
    public SSHService getSshService() { return holder.sshService; }

    @Override
    public com.runzhi.aishell.ai.AIService getAiService() { return holder.aiService; }

    @Override
    public SystemMonitor getSystemMonitor() { return holder.systemMonitor; }

    public TerminalTabService getTerminalTabService() { return holder.terminalTabService; }
    public TerminalCommandService getTerminalCommandService() { return holder.terminalCommandService; }
    public TerminalConnectionService getTerminalConnectionService() { return holder.terminalConnectionService; }
    public TerminalUIService getTerminalUIService() { return holder.terminalUIService; }


    public PerformanceMonitor getPerformanceMonitor() { return holder.performanceMonitor; }
    public ResourceOptimizer getResourceOptimizer() { return holder.resourceOptimizer; }

    // ==================== 文件管理器映射 ====================

    public void registerFileManagerController(String tabId, FileManagerPanelController controller) {
        fileManagerControllers.put(tabId, controller);
    }

    public void refreshFileListForTab(String tabId) {
        FileManagerPanelController controller = fileManagerControllers.get(tabId);
        if (controller != null) controller.refreshFileList();
    }

    public String getStylesheetPath() { return stylesheetPath; }

    // ==================== 杂项 ====================

    public void notifyTabSwitch(String tabId) {
        log.debug("Tab switched to: {}", tabId);
    }

    public boolean isInitialized() {
        return holder.sshService != null && holder.aiService != null && holder.systemMonitor != null;
    }

    public String getHealthStatus() {
        return "AIShell Module Health Status:\n" +
                "- SSH Service: " + (holder.sshService != null ? "OK" : "NOT INITIALIZED") + "\n" +
                "- AI Service: " + (holder.aiService != null ? "OK" : "NOT INITIALIZED") + "\n" +
                "- System Monitor: " + (holder.systemMonitor != null ? "OK" : "NOT INITIALIZED") + "\n" +
                "- Perf Monitor: " + (holder.performanceMonitor != null ? "OK" : "NOT INITIALIZED") + "\n" +
                "- Resource Optimizer: " + (holder.resourceOptimizer != null ? "OK" : "NOT INITIALIZED") + "\n";
    }
}