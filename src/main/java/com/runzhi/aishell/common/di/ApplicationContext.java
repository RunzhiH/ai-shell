/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.di;

import com.runzhi.aishell.common.event.EventBus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用上下文 - 整个应用的核心入口点
 * 
 * <p>提供统一的服务获取、事件发布和管理器访问接口。
 * 作为 AIShellModuleManager 的轻量级替代方案。
 * 
 * <p>使用示例：
 * <pre>
 * // 获取服务
 * SSHService sshService = ApplicationContext.getInstance().getService(SSHService.class);
 * 
 * // 发布事件
 * ApplicationContext.getInstance().publishEvent(new TabCreatedEvent(tabId));
 * 
 * // 订阅事件
 * ApplicationContext.getInstance().subscribe(TabCreatedEvent.class, event -> {
 *     // 处理事件
 * });
 * </pre>
 */
@Slf4j
@Getter
public class ApplicationContext {

    private static volatile ApplicationContext instance;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 服务注册器 */
    private final ServiceRegistry serviceRegistry;
    
    /** 事件总线 */
    private final EventBus eventBus;

    /**
     * 私有构造函数
     */
    private ApplicationContext() {
        this.serviceRegistry = new ServiceRegistry();
        this.eventBus = new EventBus();
        log.debug("ApplicationContext created");
    }

    /**
     * 获取单例实例
     *
     * @return ApplicationContext 实例
     */
    public static ApplicationContext getInstance() {
        if (instance == null) {
            synchronized (ApplicationContext.class) {
                if (instance == null) {
                    instance = new ApplicationContext();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化应用上下文
     * 此方法应该由 AIShellModuleManager 调用
     */
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            log.info("ApplicationContext initialized");
        } else {
            log.warn("ApplicationContext already initialized");
        }
    }

    /**
     * 检查是否已初始化
     *
     * @return 如果已初始化返回 true
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    // ==================== 服务相关便捷方法 ====================

    /**
     * 注册服务
     *
     * @param serviceInterface 服务接口类
     * @param implementation   服务实现实例
     * @param <T>              服务类型
     */
    public <T> void registerService(Class<T> serviceInterface, T implementation) {
        serviceRegistry.register(serviceInterface, implementation);
    }

    /**
     * 注册服务工厂
     *
     * @param serviceInterface 服务接口类
     * @param factory          服务工厂
     * @param <T>              服务类型
     */
    public <T> void registerServiceFactory(Class<T> serviceInterface, java.util.function.Supplier<T> factory) {
        serviceRegistry.registerFactory(serviceInterface, factory);
    }

    /**
     * 获取服务
     *
     * @param serviceInterface 服务接口类
     * @param <T>              服务类型
     * @return 服务实例，如果未注册则返回 null
     */
    public <T> T getService(Class<T> serviceInterface) {
        return serviceRegistry.get(serviceInterface);
    }

    /**
     * 获取或创建服务
     *
     * @param serviceInterface 服务接口类
     * @param <T>              服务类型
     * @return 服务实例
     * @throws IllegalStateException 如果服务未注册
     */
    public <T> T getOrCreateService(Class<T> serviceInterface) {
        return serviceRegistry.getOrCreate(serviceInterface);
    }

    /**
     * 检查服务是否已注册
     *
     * @param serviceInterface 服务接口类
     * @return 如果已注册返回 true
     */
    public boolean hasService(Class<?> serviceInterface) {
        return serviceRegistry.isRegistered(serviceInterface);
    }

    // ==================== 事件相关便捷方法 ====================

    /**
     * 订阅事件
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     * @param <T>       事件类型
     */
    public <T> void subscribe(Class<T> eventType, java.util.function.Consumer<T> handler) {
        eventBus.subscribe(eventType, handler);
    }

    /**
     * 取消订阅事件
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     * @param <T>       事件类型
     */
    public <T> void unsubscribe(Class<T> eventType, java.util.function.Consumer<T> handler) {
        eventBus.unsubscribe(eventType, handler);
    }

    /**
     * 发布事件
     *
     * @param event 事件对象
     * @param <T>   事件类型
     */
    public <T> void publishEvent(T event) {
        eventBus.publish(event);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭应用上下文
     */
    public void shutdown() {
        log.info("Shutting down ApplicationContext...");
        serviceRegistry.clear();
        eventBus.clear();
        initialized.set(false);
        log.info("ApplicationContext shutdown completed");
    }

    /**
     * 重置应用上下文（主要用于测试）
     */
    public static void reset() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
        initialized.set(false);
        log.debug("ApplicationContext reset");
    }
}
