/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.event;

import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 轻量级事件总线
 * 
 * <p>提供发布-订阅模式的事件分发机制，用于解耦组件之间的通信。
 * 支持弱引用订阅以避免内存泄漏。
 * 
 * <p>使用示例：
 * <pre>
 * EventBus eventBus = new EventBus();
 * 
 * // 订阅事件
 * eventBus.subscribe(TabCreatedEvent.class, event -> {
 *     System.out.println("Tab created: " + event.getTabId());
 * });
 * 
 * // 发布事件
 * eventBus.publish(new TabCreatedEvent("tab-1"));
 * 
 * // 取消订阅
 * eventBus.unsubscribe(TabCreatedEvent.class, handler);
 * </pre>
 */
@Slf4j
public class EventBus {

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<WeakReference<Consumer<?>>>> weakSubscribers = new ConcurrentHashMap<>();

    /**
     * 订阅事件
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     * @param <T>       事件类型
     */
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        if (eventType == null || handler == null) {
            throw new IllegalArgumentException("Event type and handler cannot be null");
        }
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.debug("Subscribed to event: {}", eventType.getSimpleName());
    }

    /**
     * 使用弱引用订阅事件
     * 当订阅者被垃圾回收时，订阅会自动失效
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     * @param <T>       事件类型
     */
    public <T> void subscribeWeak(Class<T> eventType, Consumer<T> handler) {
        if (eventType == null || handler == null) {
            throw new IllegalArgumentException("Event type and handler cannot be null");
        }
        weakSubscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new WeakReference<>(handler));
        log.debug("Weak subscribed to event: {}", eventType.getSimpleName());
    }

    /**
     * 取消订阅事件
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     * @param <T>       事件类型
     * @return 如果成功取消订阅返回 true
     */
    public <T> boolean unsubscribe(Class<T> eventType, Consumer<T> handler) {
        if (eventType == null || handler == null) {
            return false;
        }
        
        boolean removed = false;
        List<Consumer<?>> handlers = subscribers.get(eventType);
        if (handlers != null) {
            removed = handlers.remove(handler);
        }
        
        // 也尝试从弱引用列表中移除
        List<WeakReference<Consumer<?>>> weakHandlers = weakSubscribers.get(eventType);
        if (weakHandlers != null) {
            weakHandlers.removeIf(ref -> {
                Consumer<?> existingHandler = ref.get();
                return existingHandler == null || existingHandler.equals(handler);
            });
        }
        
        if (removed) {
            log.debug("Unsubscribed from event: {}", eventType.getSimpleName());
        }
        return removed;
    }

    /**
     * 发布事件
     * 同步通知所有订阅者
     *
     * @param event 事件对象
     * @param <T>   事件类型
     */
    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        if (event == null) {
            log.warn("Cannot publish null event");
            return;
        }
        
        Class<?> eventType = event.getClass();
        log.trace("Publishing event: {}", eventType.getSimpleName());
        
        // 通知普通订阅者
        List<Consumer<?>> handlers = subscribers.get(eventType);
        if (handlers != null) {
            for (Consumer<?> handler : handlers) {
                try {
                    ((Consumer<T>) handler).accept(event);
                } catch (Exception e) {
                    log.error("Error handling event {}: {}", eventType.getSimpleName(), e.getMessage(), e);
                }
            }
        }
        
        // 通知弱引用订阅者
        List<WeakReference<Consumer<?>>> weakHandlers = weakSubscribers.get(eventType);
        if (weakHandlers != null) {
            weakHandlers.removeIf(ref -> {
                Consumer<T> handler = (Consumer<T>) ref.get();
                if (handler == null) {
                    return true; // 移除已被 GC 的引用
                }
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    log.error("Error handling event (weak) {}: {}", eventType.getSimpleName(), e.getMessage(), e);
                }
                return false;
            });
        }
    }

    /**
     * 异步发布事件
     * 在新线程中通知所有订阅者
     *
     * @param event 事件对象
     * @param <T>   事件类型
     */
    public <T> void publishAsync(T event) {
        com.runzhi.aishell.common.util.ThreadPoolManager.getInstance()
                .getCachedThreadPool().execute(() -> publish(event));
    }

    /**
     * 检查是否有订阅者
     *
     * @param eventType 事件类型
     * @return 如果有订阅者返回 true
     */
    public boolean hasSubscribers(Class<?> eventType) {
        List<Consumer<?>> handlers = subscribers.get(eventType);
        List<WeakReference<Consumer<?>>> weakHandlers = weakSubscribers.get(eventType);
        
        boolean hasNormal = handlers != null && !handlers.isEmpty();
        boolean hasWeak = weakHandlers != null && !weakHandlers.isEmpty();
        
        return hasNormal || hasWeak;
    }

    /**
     * 获取指定事件类型的订阅者数量
     *
     * @param eventType 事件类型
     * @return 订阅者数量
     */
    public int getSubscriberCount(Class<?> eventType) {
        int count = 0;
        
        List<Consumer<?>> handlers = subscribers.get(eventType);
        if (handlers != null) {
            count += handlers.size();
        }
        
        List<WeakReference<Consumer<?>>> weakHandlers = weakSubscribers.get(eventType);
        if (weakHandlers != null) {
            // 清理无效引用并计数
            weakHandlers.removeIf(ref -> ref.get() == null);
            count += weakHandlers.size();
        }
        
        return count;
    }

    /**
     * 清除所有订阅
     */
    public void clear() {
        subscribers.clear();
        weakSubscribers.clear();
        log.debug("All event subscriptions cleared");
    }

    /**
     * 清除指定事件类型的所有订阅
     *
     * @param eventType 事件类型
     */
    public void clear(Class<?> eventType) {
        subscribers.remove(eventType);
        weakSubscribers.remove(eventType);
        log.debug("Cleared subscriptions for event: {}", eventType.getSimpleName());
    }
}
