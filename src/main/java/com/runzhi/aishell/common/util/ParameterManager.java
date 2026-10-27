/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 参数管理器 - 轻量级工具类
 * 用于在不同组件间传递临时参数
 * 
 * @author RunZhi
 * @version 1.0
 */
@Slf4j
public class ParameterManager {
    
    private static volatile ParameterManager instance;
    
    private final ObservableMap<String, Object> parameters;
    private final Map<String, Long> parameterTimestamps;
    private final long defaultTimeoutMs;
    
    private ParameterManager() {
        this.parameters = FXCollections.observableHashMap();
        this.parameterTimestamps = new HashMap<>();
        this.defaultTimeoutMs = 300000; // 5 分钟默认超时
    }
    
    /**
     * 获取单例实例
     */
    public static ParameterManager getInstance() {
        if (instance == null) {
            synchronized (ParameterManager.class) {
                if (instance == null) {
                    instance = new ParameterManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 存储参数
     */
    public void put(String key, Object value) {
        parameters.put(key, value);
        parameterTimestamps.put(key, System.currentTimeMillis());
        log.debug("Parameter stored: {}", key);
    }
    
    /**
     * 存储参数并设置超时时间
     */
    public void put(String key, Object value, long timeoutMs) {
        parameters.put(key, value);
        parameterTimestamps.put(key, System.currentTimeMillis() + timeoutMs);
        log.debug("Parameter stored with timeout: {} ({}ms)", key, timeoutMs);
    }
    
    /**
     * 获取参数并转换为指定类型
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        cleanupExpiredParameters();
        Object value = parameters.get(key);
        if (value != null && clazz.isInstance(value)) {
            return (T) value;
        }
        log.debug("Parameter not found or type mismatch: {}", key);
        return null;
    }
    
    /**
     * 获取参数
     */
    public Object get(String key) {
        cleanupExpiredParameters();
        return parameters.get(key);
    }
    
    /**
     * 检查参数是否存在
     */
    public boolean contains(String key) {
        cleanupExpiredParameters();
        return parameters.containsKey(key);
    }
    
    /**
     * 移除参数
     */
    public void remove(String key) {
        parameters.remove(key);
        parameterTimestamps.remove(key);
        log.debug("Parameter removed: {}", key);
    }
    
    /**
     * 清空所有参数
     */
    public void clear() {
        parameters.clear();
        parameterTimestamps.clear();
        log.debug("All parameters cleared");
    }
    
    /**
     * 获取参数数量
     */
    public int size() {
        cleanupExpiredParameters();
        return parameters.size();
    }
    
    /**
     * 获取所有参数键
     */
    public Set<String> keys() {
        cleanupExpiredParameters();
        return parameters.keySet();
    }
    
    /**
     * 获取所有参数的 ObservableMap (用于监听)
     */
    public ObservableMap<String, Object> getParameters() {
        cleanupExpiredParameters();
        return parameters;
    }
    
    /**
     * 清理过期参数
     */
    private void cleanupExpiredParameters() {
        long now = System.currentTimeMillis();
        parameters.keySet().removeIf(key -> {
            Long timestamp = parameterTimestamps.get(key);
            if (timestamp == null) {
                return false;
            }
            boolean expired = (now - timestamp) > defaultTimeoutMs;
            if (expired) {
                log.debug("Parameter expired: {}", key);
            }
            return expired;
        });
    }
}
