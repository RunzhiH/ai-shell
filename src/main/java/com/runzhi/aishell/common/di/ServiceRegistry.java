/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.di;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 轻量级服务注册器
 * 提供服务实例的注册、获取和懒加载创建功能
 * 
 * <p>使用示例：
 * <pre>
 * ServiceRegistry registry = new ServiceRegistry();
 * registry.register(SSHService.class, new SSHServiceImpl());
 * 
 * // 或者使用工厂模式延迟创建
 * registry.registerFactory(AIService.class, AIServiceImpl::new);
 * 
 * // 获取服务
 * SSHService sshService = registry.get(SSHService.class);
 * </pre>
 */
@Slf4j
public class ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<?>> factories = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();

    /**
     * 注册服务实例
     *
     * @param serviceInterface 服务接口类
     * @param implementation   服务实现实例
     * @param <T>              服务类型
     */
    public <T> void register(Class<T> serviceInterface, T implementation) {
        if (serviceInterface == null || implementation == null) {
            throw new IllegalArgumentException("Service interface and implementation cannot be null");
        }
        services.put(serviceInterface, implementation);
        log.debug("Registered service: {} -> {}", serviceInterface.getSimpleName(), 
                  implementation.getClass().getSimpleName());
    }

    /**
     * 注册服务工厂（延迟创建）
     *
     * @param serviceInterface 服务接口类
     * @param factory          服务工厂
     * @param <T>              服务类型
     */
    public <T> void registerFactory(Class<T> serviceInterface, Supplier<T> factory) {
        if (serviceInterface == null || factory == null) {
            throw new IllegalArgumentException("Service interface and factory cannot be null");
        }
        factories.put(serviceInterface, factory);
        log.debug("Registered factory for service: {}", serviceInterface.getSimpleName());
    }

    /**
     * 获取服务实例
     *
     * @param serviceInterface 服务接口类
     * @param <T>              服务类型
     * @return 服务实例，如果未注册则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> serviceInterface) {
        // 首先检查直接注册的服务
        Object service = services.get(serviceInterface);
        if (service != null) {
            return (T) service;
        }
        
        // 然后检查单例缓存
        service = singletons.get(serviceInterface);
        if (service != null) {
            return (T) service;
        }
        
        log.debug("Service not found: {}", serviceInterface.getSimpleName());
        return null;
    }

    /**
     * 获取或创建服务实例
     * 如果服务已注册实例，直接返回
     * 如果服务注册了工厂，则通过工厂创建并缓存为单例
     *
     * @param serviceInterface 服务接口类
     * @param <T>              服务类型
     * @return 服务实例
     * @throws IllegalStateException 如果服务未注册
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCreate(Class<T> serviceInterface) {
        // 首先检查直接注册的服务
        Object service = services.get(serviceInterface);
        if (service != null) {
            return (T) service;
        }
        
        // 检查单例缓存
        service = singletons.get(serviceInterface);
        if (service != null) {
            return (T) service;
        }
        
        // 通过工厂创建
        Supplier<?> factory = factories.get(serviceInterface);
        if (factory != null) {
            synchronized (this) {
                // 双重检查
                service = singletons.get(serviceInterface);
                if (service == null) {
                    service = factory.get();
                    singletons.put(serviceInterface, service);
                    log.debug("Created service instance via factory: {} -> {}", 
                              serviceInterface.getSimpleName(), 
                              service.getClass().getSimpleName());
                }
            }
            return (T) service;
        }
        
        throw new IllegalStateException("Service not registered: " + serviceInterface.getSimpleName());
    }

    /**
     * 检查服务是否已注册
     *
     * @param serviceInterface 服务接口类
     * @return 如果服务已注册（实例或工厂）则返回 true
     */
    public boolean isRegistered(Class<?> serviceInterface) {
        return services.containsKey(serviceInterface) || factories.containsKey(serviceInterface);
    }

    /**
     * 移除服务注册
     *
     * @param serviceInterface 服务接口类
     * @return 被移除的服务实例，如果不存在则返回 null
     */
    public Object unregister(Class<?> serviceInterface) {
        factories.remove(serviceInterface);
        singletons.remove(serviceInterface);
        Object removed = services.remove(serviceInterface);
        if (removed != null) {
            log.debug("Unregistered service: {}", serviceInterface.getSimpleName());
        }
        return removed;
    }

    /**
     * 清除所有注册的服务
     */
    public void clear() {
        services.clear();
        factories.clear();
        singletons.clear();
        log.debug("All services cleared");
    }

    /**
     * 获取已注册服务的数量
     *
     * @return 已注册服务的数量（包括实例和工厂）
     */
    public int size() {
        // 使用 Set 去重，因为同一个服务可能同时存在于 services 和 factories
        java.util.Set<Class<?>> allKeys = new java.util.HashSet<>();
        allKeys.addAll(services.keySet());
        allKeys.addAll(factories.keySet());
        return allKeys.size();
    }

    /**
     * 获取所有已注册服务的接口类
     *
     * @return 已注册服务接口类的集合
     */
    public java.util.Set<Class<?>> getRegisteredServices() {
        java.util.Set<Class<?>> allKeys = new java.util.HashSet<>();
        allKeys.addAll(services.keySet());
        allKeys.addAll(factories.keySet());
        return allKeys;
    }
}
