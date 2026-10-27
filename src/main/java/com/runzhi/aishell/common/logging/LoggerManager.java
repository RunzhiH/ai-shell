/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一日志管理器，提供统一的日志记录接口
 * 增强功能：诊断信息、结构化日志、性能追踪
 */
public class LoggerManager {
    private static final Logger logger = LoggerFactory.getLogger(LoggerManager.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, Long> performanceTrackers = new ConcurrentHashMap<>();
    
    // 日志上下文键
    private static final String SESSION_ID_KEY = "sessionId";
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String USER_ID_KEY = "userId";
    private static final String TAB_ID_KEY = "tabId";
    private static final String OPERATION_KEY = "operation";
    
    // 诊断信息级别
    public enum DiagnosticLevel {
        BASIC,       // 基础信息
        DETAILED,    // 详细信息
        VERBOSE      // 详细调试信息
    }

    /**
     * 获取指定类的日志记录器
     *
     * @param clazz 指定的类
     * @return Logger实例
     */
    public static <T> Logger getLogger(Class<T> clazz) {
        return LoggerFactory.getLogger(clazz);
    }

    /**
     * 获取LoggerManager类的日志记录器
     *
     * @return Logger实例
     */
    public static Logger getLogger() {
        return logger;
    }

    /**
     * 记录DEBUG级别的日志
     *
     * @param message 日志消息
     */
    public static void debug(String message) {
        logger.debug(message);
    }

    /**
     * 记录INFO级别的日志
     *
     * @param message 日志消息
     */
    public static void info(String message) {
        logger.info(message);
    }

    /**
     * 记录WARN级别的日志
     *
     * @param message 日志消息
     */
    public static void warn(String message) {
        logger.warn(message);
    }

    /**
     * 记录ERROR级别的日志
     *
     * @param message 日志消息
     */
    public static void error(String message) {
        logger.error(message);
    }

    /**
     * 记录ERROR级别的日志并包含异常信息
     *
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
    
    /**
     * 记录DEBUG级别的日志并包含异常信息
     *
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void debug(String message, Throwable throwable) {
        logger.debug(message, throwable);
    }

    /**
     * 记录INFO级别的日志并包含异常信息
     *
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void info(String message, Throwable throwable) {
        logger.info(message, throwable);
    }

    /**
     * 记录WARN级别的日志并包含异常信息
     *
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void warn(String message, Throwable throwable) {
        logger.warn(message, throwable);
    }
    
    /**
     * 记录指定级别的日志
     *
     * @param level 日志级别 (debug, info, warn, error)
     * @param message 日志消息
     */
    public static void log(String level, String message) {
        if (level == null) {
            logger.info(message);
            return;
        }
        
        switch (level.toLowerCase()) {
            case "debug": 
                logger.debug(message);
                break;
            case "info": 
                logger.info(message);
                break;
            case "warn": 
                logger.warn(message);
                break;
            case "error": 
                logger.error(message);
                break;
            default:
                logger.info(message);
        }
    }
    
    /**
     * 记录指定级别的日志并包含异常信息
     *
     * @param level 日志级别 (debug, info, warn, error)
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void log(String level, String message, Throwable throwable) {
        if (level == null) {
            logger.info(message, throwable);
            return;
        }
        
        switch (level.toLowerCase()) {
            case "debug": 
                logger.debug(message, throwable);
                break;
            case "info": 
                logger.info(message, throwable);
                break;
            case "warn": 
                logger.warn(message, throwable);
                break;
            case "error": 
                logger.error(message, throwable);
                break;
            default:
                logger.info(message, throwable);
        }
    }
    
    // ==================== 增强日志功能 ====================
    
    /**
     * 记录带诊断信息的日志
     */
    public static void logWithDiagnostics(String level, String message, 
                                        DiagnosticLevel diagLevel, Object... params) {
        StringBuilder sb = new StringBuilder();
        sb.append(message);
        
        // 添加诊断信息
        if (diagLevel.ordinal() >= DiagnosticLevel.DETAILED.ordinal()) {
            sb.append(" [Diag: ");
            sb.append(TIMESTAMP_FORMATTER.format(LocalDateTime.now()));
            sb.append("]");
        }
        
        if (diagLevel == DiagnosticLevel.VERBOSE && params.length > 0) {
            sb.append(" [Params: ");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i]);
            }
            sb.append("]");
        }
        
        log(level, sb.toString());
    }
    
    /**
     * 记录结构化日志
     */
    public static void logStructured(String level, String operation, String status, 
                                   Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":\"").append(TIMESTAMP_FORMATTER.format(LocalDateTime.now())).append("\"");
        sb.append(",\"operation\":\"").append(operation).append("\"");
        sb.append(",\"status\":\"").append(status).append("\"");
        
        if (fields != null) {
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                sb.append(",\"").append(entry.getKey()).append("\":\"");
                sb.append(entry.getValue()).append("\"");
            }
        }
        
        sb.append("}");
        log(level, sb.toString());
    }
    
    /**
     * 开始性能追踪
     */
    public static void startPerformanceTracking(String operationName) {
        performanceTrackers.put(operationName, System.nanoTime());
        debug("开始性能追踪: " + operationName);
    }
    
    /**
     * 结束性能追踪并记录耗时
     */
    public static void endPerformanceTracking(String operationName) {
        Long startTime = performanceTrackers.remove(operationName);
        if (startTime != null) {
            long duration = System.nanoTime() - startTime;
            double durationMs = duration / 1_000_000.0;
            info(String.format("性能追踪结束: %s, 耗时: %.2f ms", operationName, durationMs));
        }
    }
    
    /**
     * 记录方法进入
     */
    public static void enterMethod(String className, String methodName, Object... params) {
        MDC.put(OPERATION_KEY, className + "." + methodName);
        StringBuilder sb = new StringBuilder();
        sb.append("进入方法: ").append(className).append(".").append(methodName);
        
        if (params.length > 0) {
            sb.append(" 参数: [");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i]);
            }
            sb.append("]");
        }
        
        debug(sb.toString());
    }
    
    /**
     * 记录方法退出
     */
    public static void exitMethod(String className, String methodName, Object returnValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("退出方法: ").append(className).append(".").append(methodName);
        
        if (returnValue != null) {
            sb.append(" 返回值: ").append(returnValue);
        }
        
        debug(sb.toString());
        MDC.remove(OPERATION_KEY);
    }
    
    /**
     * 设置日志上下文
     */
    public static void setLogContext(String key, String value) {
        MDC.put(key, value);
    }
    
    /**
     * 清除日志上下文
     */
    public static void clearLogContext(String key) {
        MDC.remove(key);
    }
    
    /**
     * 清除所有日志上下文
     */
    public static void clearAllContext() {
        MDC.clear();
    }
    
    /**
     * 记录系统状态信息
     */
    public static void logSystemStatus() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        Map<String, Object> statusFields = new java.util.HashMap<>();
        statusFields.put("usedMemoryMB", usedMemory / (1024 * 1024));
        statusFields.put("freeMemoryMB", freeMemory / (1024 * 1024));
        statusFields.put("totalMemoryMB", totalMemory / (1024 * 1024));
        statusFields.put("maxMemoryMB", maxMemory / (1024 * 1024));
        statusFields.put("availableProcessors", runtime.availableProcessors());
        
        logStructured("INFO", "SYSTEM_STATUS", "REPORT", statusFields);
    }
    
    /**
     * 记录安全相关事件
     */
    public static void logSecurityEvent(String eventType, String userId, String details) {
        Map<String, Object> securityFields = new java.util.HashMap<>();
        securityFields.put("eventType", eventType);
        securityFields.put("userId", userId);
        securityFields.put("details", details);
        securityFields.put("clientIP", getClientIP());
        
        logStructured("WARN", "SECURITY_EVENT", "TRIGGERED", securityFields);
    }
    
    /**
     * 获取客户端IP地址（模拟实现）
     */
    private static String getClientIP() {
        // 在实际应用中，这里应该从请求上下文中获取真实IP
        return "127.0.0.1";
    }
}