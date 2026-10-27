/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.validation;

import com.runzhi.aishell.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 边界检查工具类
 * 提供各种边界条件检查和安全操作
 */
@Slf4j
public class BoundaryChecker {
    
    // 系统资源边界常量
    public static final int MAX_STRING_LENGTH = 10000;
    public static final int MAX_ARRAY_SIZE = 10000;
    public static final int MAX_COLLECTION_SIZE = 10000;
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100MB
    public static final int MAX_CONNECTION_ATTEMPTS = 10;
    public static final long MAX_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10); // 10分钟
    public static final int MAX_RETRY_COUNT = 5;
    public static final int MAX_THREAD_POOL_SIZE = 100;
    
    // 私有构造函数防止实例化
    private BoundaryChecker() {}
    
    /**
     * 检查字符串长度边界
     */
    public static void checkStringLength(String str, String fieldName, int maxLength) {
        if (str == null) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (str.length() > maxLength) {
            throw new ValidationException(fieldName + "长度超出限制(" + maxLength + "字符)");
        }
    }
    
    /**
     * 检查数组大小边界
     */
    public static void checkArraySize(Object[] array, String fieldName, int maxSize) {
        if (array == null) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (array.length > maxSize) {
            throw new ValidationException(fieldName + "大小超出限制(" + maxSize + "元素)");
        }
    }
    
    /**
     * 检查集合大小边界
     */
    public static void checkCollectionSize(java.util.Collection<?> collection, String fieldName, int maxSize) {
        if (collection == null) {
            throw new ValidationException(fieldName + "不能为空");
        }
        if (collection.size() > maxSize) {
            throw new ValidationException(fieldName + "大小超出限制(" + maxSize + "元素)");
        }
    }
    
    /**
     * 检查数值上下限
     */
    public static void checkNumericBounds(Number value, String fieldName, Number min, Number max) {
        if (value == null) {
            throw new ValidationException(fieldName + "不能为空");
        }
        
        double doubleValue = value.doubleValue();
        double minValue = min != null ? min.doubleValue() : Double.NEGATIVE_INFINITY;
        double maxValue = max != null ? max.doubleValue() : Double.POSITIVE_INFINITY;
        
        if (doubleValue < minValue || doubleValue > maxValue) {
            String minStr = min != null ? min.toString() : "-∞";
            String maxStr = max != null ? max.toString() : "+∞";
            throw new ValidationException(fieldName + "必须在" + minStr + "到" + maxStr + "之间");
        }
    }
    
    /**
     * 检查时间超时边界
     */
    public static void checkTimeout(long timeoutMillis, String fieldName) {
        if (timeoutMillis <= 0) {
            throw new ValidationException(fieldName + "必须大于0");
        }
        if (timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new ValidationException(fieldName + "超出最大限制(" + MAX_TIMEOUT_MILLIS + "毫秒)");
        }
    }
    
    /**
     * 检查重试次数边界
     */
    public static void checkRetryCount(int retryCount, String fieldName) {
        if (retryCount < 0) {
            throw new ValidationException(fieldName + "不能为负数");
        }
        if (retryCount > MAX_RETRY_COUNT) {
            throw new ValidationException(fieldName + "超出最大重试次数(" + MAX_RETRY_COUNT + ")");
        }
    }
    
    /**
     * 检查线程池大小边界
     */
    public static void checkThreadPoolSize(int poolSize, String fieldName) {
        if (poolSize <= 0) {
            throw new ValidationException(fieldName + "必须大于0");
        }
        if (poolSize > MAX_THREAD_POOL_SIZE) {
            throw new ValidationException(fieldName + "超出最大线程池大小(" + MAX_THREAD_POOL_SIZE + ")");
        }
    }
    
    /**
     * 检查内存使用率边界
     */
    public static void checkMemoryUsage(double memoryUsagePercent, String fieldName) {
        if (memoryUsagePercent < 0 || memoryUsagePercent > 100) {
            throw new ValidationException(fieldName + "必须在0-100之间");
        }
        if (memoryUsagePercent > 95) {
            log.warn("{} 达到警告阈值: {}%", fieldName, memoryUsagePercent);
        }
        if (memoryUsagePercent > 98) {
            throw new ValidationException(fieldName + "超出安全阈值(98%)");
        }
    }
    
    /**
     * 检查CPU使用率边界
     */
    public static void checkCpuUsage(double cpuUsagePercent, String fieldName) {
        if (cpuUsagePercent < 0 || cpuUsagePercent > 100) {
            throw new ValidationException(fieldName + "必须在0-100之间");
        }
        if (cpuUsagePercent > 90) {
            log.warn("{} 达到警告阈值: {}%", fieldName, cpuUsagePercent);
        }
        if (cpuUsagePercent > 95) {
            throw new ValidationException(fieldName + "超出安全阈值(95%)");
        }
    }
    
    /**
     * 检查磁盘空间边界
     */
    public static void checkDiskSpace(long freeSpaceBytes, long totalSpaceBytes, String fieldName) {
        if (freeSpaceBytes < 0 || totalSpaceBytes <= 0) {
            throw new ValidationException(fieldName + "空间数据无效");
        }
        if (freeSpaceBytes > totalSpaceBytes) {
            throw new ValidationException(fieldName + "可用空间不能大于总空间");
        }
        
        double freeSpacePercent = (double) freeSpaceBytes / totalSpaceBytes * 100;
        if (freeSpacePercent < 5) {
            log.warn("{} 空间不足: {:.2f}% ({}/{})", fieldName, freeSpacePercent, freeSpaceBytes, totalSpaceBytes);
        }
        if (freeSpacePercent < 1) {
            throw new ValidationException(fieldName + "空间严重不足(<1%)");
        }
    }
    
    /**
     * 检查网络连接数边界
     */
    public static void checkConnectionCount(int connectionCount, int maxConnections, String fieldName) {
        if (connectionCount < 0) {
            throw new ValidationException(fieldName + "连接数不能为负数");
        }
        if (connectionCount > maxConnections) {
            throw new ValidationException(fieldName + "超出最大连接数限制(" + maxConnections + ")");
        }
        if (connectionCount > maxConnections * 0.8) {
            log.warn("{} 连接数接近上限: {}/{}", fieldName, connectionCount, maxConnections);
        }
    }
    
    /**
     * 检查缓冲区大小边界
     */
    public static void checkBufferSize(int bufferSize, String fieldName, int maxSize) {
        if (bufferSize <= 0) {
            throw new ValidationException(fieldName + "缓冲区大小必须大于0");
        }
        if (bufferSize > maxSize) {
            throw new ValidationException(fieldName + "缓冲区大小超出限制(" + maxSize + "字节)");
        }
    }
    
    /**
     * 检查文件大小边界
     */
    public static void checkFileSize(long fileSizeBytes, String fieldName) {
        if (fileSizeBytes < 0) {
            throw new ValidationException(fieldName + "文件大小不能为负数");
        }
        if (fileSizeBytes > MAX_FILE_SIZE) {
            throw new ValidationException(fieldName + "文件大小超出限制(" + 
                (MAX_FILE_SIZE / (1024 * 1024)) + "MB)");
        }
    }
    
    /**
     * 安全的数学运算，防止溢出
     */
    public static int safeAdd(int a, int b) {
        if (b > 0 && a > Integer.MAX_VALUE - b) {
            throw new ArithmeticException("整数加法溢出");
        }
        if (b < 0 && a < Integer.MIN_VALUE - b) {
            throw new ArithmeticException("整数减法溢出");
        }
        return a + b;
    }
    
    /**
     * 安全的长整型加法
     */
    public static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            throw new ArithmeticException("长整型加法溢出");
        }
        if (b < 0 && a < Long.MIN_VALUE - b) {
            throw new ArithmeticException("长整型减法溢出");
        }
        return a + b;
    }
    
    /**
     * 安全的乘法运算
     */
    public static int safeMultiply(int a, int b) {
        if (a > 0 && b > 0 && a > Integer.MAX_VALUE / b) {
            throw new ArithmeticException("整数乘法溢出");
        }
        if (a > 0 && b < 0 && b < Integer.MIN_VALUE / a) {
            throw new ArithmeticException("整数乘法溢出");
        }
        if (a < 0 && b > 0 && a < Integer.MIN_VALUE / b) {
            throw new ArithmeticException("整数乘法溢出");
        }
        if (a < 0 && b < 0 && a < Integer.MAX_VALUE / b) {
            throw new ArithmeticException("整数乘法溢出");
        }
        return a * b;
    }
    
    /**
     * 安全的除法运算，防止除零
     */
    public static int safeDivide(int dividend, int divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("除数不能为零");
        }
        return dividend / divisor;
    }
    
    /**
     * 安全的长整型除法
     */
    public static long safeDivide(long dividend, long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("除数不能为零");
        }
        return dividend / divisor;
    }
    
    /**
     * 检查系统资源使用情况
     */
    public static SystemResourceStatus checkSystemResources() {
        SystemResourceStatus status = new SystemResourceStatus();
        
        try {
            // 检查内存使用情况
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
            
            status.memoryUsagePercent = memoryUsagePercent;
            status.memoryOk = memoryUsagePercent <= 95;
            
            // 检查可用处理器数量
            int processors = runtime.availableProcessors();
            status.cpuCores = processors;
            status.cpuOk = true; // CPU核心数通常不会有问题
            
            // 检查临时空间
            java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"));
            long freeSpace = tempDir.getFreeSpace();
            long totalSpace = tempDir.getTotalSpace();
            
            status.diskSpaceOk = freeSpace > (totalSpace * 0.05); // 至少5%空闲空间
            
        } catch (Exception e) {
            log.error("检查系统资源时发生错误", e);
            status.memoryOk = false;
            status.cpuOk = false;
            status.diskSpaceOk = false;
        }
        
        return status;
    }
    
    /**
     * 系统资源状态类
     */
    public static class SystemResourceStatus {
        public double memoryUsagePercent = 0.0;
        public int cpuCores = 0;
        public boolean memoryOk = true;
        public boolean cpuOk = true;
        public boolean diskSpaceOk = true;
        
        public boolean isHealthy() {
            return memoryOk && cpuOk && diskSpaceOk;
        }
        
        @Override
        public String toString() {
            return String.format("SystemResourceStatus{memory=%.2f%%, cores=%d, memoryOk=%s, cpuOk=%s, diskOk=%s}",
                memoryUsagePercent, cpuCores, memoryOk, cpuOk, diskSpaceOk);
        }
    }
}