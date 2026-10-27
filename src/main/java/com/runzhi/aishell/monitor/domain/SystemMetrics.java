/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SystemMetrics {
    private String timestamp;      // 时间戳
    private String uptime;         // 系统运行时间
    private int userCount;         // 当前用户数
    private double loadAverage1;   // 1分钟平均负载
    private double loadAverage5;   // 5分钟平均负载
    private double loadAverage15;  // 15分钟平均负载
    private int totalTasks;        // 总进程数
    private int runningTasks;      // 运行中进程数
    private int sleepingTasks;     // 睡眠中进程数
    private int stoppedTasks;      // 停止进程数
    private int zombieTasks;       // 僵尸进程数
    private double cpuUser;        // 用户CPU使用率
    private double cpuSystem;      // 系统CPU使用率
    private double cpuNice;        // nice进程CPU使用率
    private double cpuIdle;        // 空闲CPU使用率
    private double cpuWait;        // 等待IO的CPU使用率
    private double cpuHardwareInterrupt; // 硬件中断CPU使用率
    private double cpuSoftwareInterrupt; // 软件中断CPU使用率
    private double cpuSteal;       // 虚拟化窃取CPU使用率
    private long totalMemory;      // 总内存(KiB)
    private long freeMemory;       // 空闲内存(KiB)
    private long usedMemory;       // 已用内存(KiB)
    private long bufferCacheMemory; // 缓冲/缓存内存(KiB)
    private long totalSwap;        // 总交换空间(KiB)
    private long freeSwap;         // 空闲交换空间(KiB)
    private long usedSwap;         // 已用交换空间(KiB)
    private long availableMemory;  // 可用内存(KiB)
    private String netUp;          // 网络上传速度
    private String netDown;        // 网络下载速度
    private double diskUsage;      // 磁盘使用率（百分比）
    private String diskUsageString; // 磁盘使用率字符串（如 "35%")
    
    // 添加缺失的字段
    private int totalProcesses;    // 总进程数
    private long diskReadBytes;    // 磁盘读取字节数
    private long diskWriteBytes;   // 磁盘写入字节数
    private long totalRxBytes;     // 总接收字节数
    private long totalTxBytes;     // 总发送字节数
    
    // 网络连接数字段
    private int establishedConnections; // ESTABLISHED状态连接数
    private int listeningConnections;   // LISTEN状态连接数
}