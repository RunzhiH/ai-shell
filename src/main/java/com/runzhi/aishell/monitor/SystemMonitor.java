/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SystemMonitor {
    private DataSourceProvider dataSourceProvider;
    private volatile boolean monitoring = false;
    private volatile Thread monitorThread;
    private MonitorCallback callback; // 添加回调接口
    /** CPU /proc/stat 上次采样的各字段累计值，用于两次采样间的增量百分比（2026-10-23 审计 P1-16）。 */
    private long[] prevCpuFields;
    /** 网络字节上次采样值与采样时刻，用于计算速率（netUp/netDown 字段语义是速度而非累计总量）。 */
    private long prevRxBytes = -1;
    private long prevTxBytes = -1;
    private long prevNetSampleTimeMs = 0;
    /** 可配置的监控间隔；setMonitorInterval 此前是空方法，公共 API 静默忽略入参（2026-10-23 审计终端-23）。 */
    private volatile long monitorIntervalMs = AppConstants.MONITOR_INTERVAL_MS;

    // 保留原有的构造函数以向后兼容
    public SystemMonitor(SSHConnectionManager connectionManager) {
        this.dataSourceProvider = new SSHConnectionManagerAdapter(connectionManager);
    }

    // 新的构造函数，接受数据源提供者
    public SystemMonitor(DataSourceProvider dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    public synchronized void startMonitoring() {
        if (monitorThread != null && monitorThread.isAlive()) return;
        monitoring = true;
        monitorThread = new Thread(this::monitorLoop, "ssh-system-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void monitorLoop() {
        while (monitoring && dataSourceProvider.isConnected()) {
            try {
                // 获取系统指标
                SystemMetrics metrics = getSystemMetrics();

                // 通过回调更新UI，使用 SystemMetrics 对象
                if (callback != null && metrics != null) {
                    callback.onMetricsUpdate(metrics);
                }

                Thread.sleep(monitorIntervalMs); // 使用配置的监控间隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error during monitoring", e);
                // 不再直接break，而是继续尝试监控
                try {
                    Thread.sleep(monitorIntervalMs); // 等待后再继续
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break; // 如果被中断，则退出
                }
            }
        }
    }

    /**
     * 获取系统指标并解析为 SystemMetrics 对象
     *
     * @return SystemMetrics 对象
     */
    public SystemMetrics getSystemMetrics() {
        try {
            // 尝试使用更简单的命令来获取系统指标，避免top命令可能的超时问题
            SystemMetrics metrics = SystemMetrics.builder().build();

            // 并行执行多个命令以提高效率
            // 先获取磁盘使用率
            String dfOutput = executeCommandWithTimeout("df -h", AppConstants.DF_COMMAND_TIMEOUT);
            if (dfOutput != null && !dfOutput.isEmpty()) {
                double diskUsage = parseDiskUsage(dfOutput);
                metrics.setDiskUsage(diskUsage);
                metrics.setDiskUsageString(String.format("%.1f%%", diskUsage));
            }

            // 尝试获取基本系统信息
            String uptimeOutput = executeCommandWithTimeout("uptime", AppConstants.UPTIME_COMMAND_TIMEOUT);
            if (uptimeOutput != null && !uptimeOutput.isEmpty()) {
                parseUptimeOutput(uptimeOutput, metrics);
            }

            // 获取内存信息，使用更轻量级的命令
            String memOutput = executeCommandWithTimeout("free -k", AppConstants.FREE_COMMAND_TIMEOUT);
            if (memOutput != null && !memOutput.isEmpty()) {
                parseFreeOutput(memOutput, metrics);
            }

            // 获取CPU信息
            String cpuStatOutput = executeCommandWithTimeout("cat /proc/stat | head -n 1", AppConstants.CPUSTAT_COMMAND_TIMEOUT);
            if (cpuStatOutput != null && !cpuStatOutput.isEmpty()) {
                parseCpuStatOutput(cpuStatOutput, metrics);
            }

            // 获取网络信息
            // 直接读取 /proc/net/dev：远端 grep 的接口名白名单（eth|wlan|enp|wlp）会漏掉
            // ens33/eno1/enx 等主流命名，此类机器网络指标恒为 0（2026-10-23 审计 P1-16）
            String netOutput = executeCommandWithTimeout("cat /proc/net/dev", AppConstants.NET_COMMAND_TIMEOUT);
            if (netOutput != null && !netOutput.isEmpty()) {
                parseNetworkOutput(netOutput, metrics);
            }

            // 获取进程信息
            String psOutput = executeCommandWithTimeout("ps aux --no-headers | wc -l", AppConstants.PROCESS_COMMAND_TIMEOUT);
            if (psOutput != null && !psOutput.isEmpty()) {
                try {
                    int processCount = Integer.parseInt(psOutput.trim());
                    metrics.setTotalProcesses(processCount);
                } catch (NumberFormatException e) {
                    log.warn("Could not parse process count: {}", psOutput);
                }
            }

            // 获取磁盘I/O信息（如果可用）
            String diskIoOutput = executeCommandWithTimeout("cat /proc/diskstats 2>/dev/null | head -n 5", AppConstants.PROCESS_COMMAND_TIMEOUT);
            if (diskIoOutput != null && !diskIoOutput.isEmpty()) {
                parseDiskIoOutput(diskIoOutput, metrics);
            }

            // 如果需要更详细的信息，可以尝试top命令，但使用更长的超时
            // 现在将其放在最后以避免阻塞
            try {
                String topOutput = executeCommandWithTimeout(
                        "export LANG=\"en_US\"; export LANGUAGE=\"en_US\"; export LC_ALL=\"en_US\"; timeout 15 top -b -n 1",
                        AppConstants.TOP_COMMAND_TIMEOUT
                );
                SystemMetrics topMetrics = parseTopOutput(topOutput);
                if (topMetrics != null) {
                    // 合并top解析的结果
                    mergeMetrics(metrics, topMetrics);
                }
            } catch (Exception e) {
                log.warn("Could not execute top command, using basic metrics only: {}", e.getMessage());
            }

            return metrics;
        } catch (Exception e) {
            log.error("Error getting system metrics", e);
            return null;
        }
    }

    /**
     * 解析磁盘I/O信息
     */
    private void parseDiskIoOutput(String diskIoOutput, SystemMetrics metrics) {
        if (diskIoOutput == null || diskIoOutput.isEmpty()) {
            return;
        }

        String[] lines = diskIoOutput.split("\n");
        long totalRead = 0;
        long totalWrite = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 解析磁盘I/O统计信息
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 14) {
                try {
                    // 字段6是读取的扇区数，字段10是写入的扇区数
                    long sectorsRead = Long.parseLong(fields[5]); // 读取扇区数
                    long sectorsWritten = Long.parseLong(fields[9]); // 写入扇区数
                    
                    totalRead += sectorsRead;
                    totalWrite += sectorsWritten;
                } catch (NumberFormatException e) {
                    // 忽略解析错误
                    log.debug("Could not parse disk I/O data: {}", line);
                }
            }
        }

        // 将扇区转换为字节（假设每扇区512字节）
        metrics.setDiskReadBytes(totalRead * 512);
        metrics.setDiskWriteBytes(totalWrite * 512);
    }

    /**
     * 解析 top 命令输出并生成 SystemMetrics 对象
     *
     * @param topOutput top 命令的输出
     * @return SystemMetrics 对象
     */
    private SystemMetrics parseTopOutput(String topOutput) {
        if (topOutput == null || topOutput.isEmpty()) {
            return null;
        }

        SystemMetrics metrics = SystemMetrics.builder().build();
        String[] lines = topOutput.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 解析时间戳和运行时间
            if (line.startsWith("top - ")) {
                // 例如: top - 09:45:21 up 52 days, 19:03, 4 users, load average: 0.15, 0.15, 0.13
                parseTimestampAndUptime(line, metrics);
            }
            // 解析任务信息
            else if (line.startsWith("Tasks:")) {
                parseTasks(line, metrics);
            }
            // 解析CPU信息
            else if (line.startsWith("%Cpu(s):")) {
                parseCpu(line, metrics);
            }
            // 解析内存信息
            else if (line.contains("KiB Mem")) {
                parseMemory(line, metrics);
            }
            // 解析交换空间信息
            else if (line.contains("KiB Swap")) {
                parseSwap(line, metrics);
            }
        }

        return metrics;
    }

    private void parseTimestampAndUptime(String line, SystemMetrics metrics) {
        // 正则表达式解析时间戳和运行时间
        Pattern pattern = Pattern.compile("top - (\\d{2}:\\d{2}:\\d{2}) up ([^,]+),\\s*(\\d+) users?,\\s*load average:\\s*([\\d.]+),\\s*([\\d.]+),\\s*([\\d.]+)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            metrics.setTimestamp(matcher.group(1)); // 时间戳
            metrics.setUptime(matcher.group(2));    // 运行时间
            metrics.setUserCount(Integer.parseInt(matcher.group(3))); // 用户数
            metrics.setLoadAverage1(Double.parseDouble(matcher.group(4))); // 1分钟平均负载
            metrics.setLoadAverage5(Double.parseDouble(matcher.group(5))); // 5分钟平均负载
            metrics.setLoadAverage15(Double.parseDouble(matcher.group(6))); // 15分钟平均负载
        }
    }

    private void parseTasks(String line, SystemMetrics metrics) {
        // 例如: Tasks: 122 total, 1 running, 121 sleeping, 0 stopped, 0 zombie
        Pattern pattern = Pattern.compile("Tasks:\\s*(\\d+) total,\\s*(\\d+) running,\\s*(\\d+) sleeping,\\s*(\\d+) stopped,\\s*(\\d+) zombie");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            metrics.setTotalTasks(Integer.parseInt(matcher.group(1)));
            metrics.setRunningTasks(Integer.parseInt(matcher.group(2)));
            metrics.setSleepingTasks(Integer.parseInt(matcher.group(3)));
            metrics.setStoppedTasks(Integer.parseInt(matcher.group(4)));
            metrics.setZombieTasks(Integer.parseInt(matcher.group(5)));
        }
    }

    private void parseCpu(String line, SystemMetrics metrics) {
        // 例如: %Cpu(s): 1.6 us, 1.6 sy, 0.0 ni, 96.8 id, 0.0 wa, 0.0 hi, 0.0 si, 0.0 st
        Pattern pattern = Pattern.compile("%Cpu\\(s\\):\\s*([\\d.]+) us,\\s*([\\d.]+) sy,\\s*([\\d.]+) ni,\\s*([\\d.]+) id,\\s*([\\d.]+) wa,\\s*([\\d.]+) hi,\\s*([\\d.]+) si,\\s*([\\d.]+) st");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            metrics.setCpuUser(Double.parseDouble(matcher.group(1))); // 用户CPU使用率
            metrics.setCpuSystem(Double.parseDouble(matcher.group(2))); // 系统CPU使用率
            metrics.setCpuNice(Double.parseDouble(matcher.group(3))); // nice进程CPU使用率
            metrics.setCpuIdle(Double.parseDouble(matcher.group(4))); // 空闲CPU使用率
            metrics.setCpuWait(Double.parseDouble(matcher.group(5))); // 等待IO的CPU使用率
            metrics.setCpuHardwareInterrupt(Double.parseDouble(matcher.group(6))); // 硬件中断CPU使用率
            metrics.setCpuSoftwareInterrupt(Double.parseDouble(matcher.group(7))); // 软件中断CPU使用率
            metrics.setCpuSteal(Double.parseDouble(matcher.group(8))); // 虚拟化窃取CPU使用率
        }
    }

    private void parseMemory(String line, SystemMetrics metrics) {
        // 例如: KiB Mem : 3782380 total, 797540 free, 324120 used, 2660720 buff/cache
        Pattern pattern = Pattern.compile("KiB Mem\\s*:\\s*(\\d+) total,\\s*(\\d+) free,\\s*(\\d+) used,\\s*(\\d+) buff/cache");
        Matcher matcher = pattern.matcher(line.replaceAll("\\s+", " ")); // 标准化空格
        if (matcher.find()) {
            metrics.setTotalMemory(Long.parseLong(matcher.group(1))); // 总内存
            metrics.setFreeMemory(Long.parseLong(matcher.group(2))); // 空闲内存
            metrics.setUsedMemory(Long.parseLong(matcher.group(3))); // 已用内存
            metrics.setBufferCacheMemory(Long.parseLong(matcher.group(4))); // 缓冲/缓存内存
        }

        // 查找可用内存（通常在下一行或同一行的可用列）
        // 如果没有单独的可用内存信息，则使用free + buff/cache作为可用内存
        if (metrics.getAvailableMemory() == 0 && metrics.getFreeMemory() > 0 && metrics.getBufferCacheMemory() > 0) {
            metrics.setAvailableMemory(metrics.getFreeMemory() + metrics.getBufferCacheMemory());
        }
    }

    private void parseSwap(String line, SystemMetrics metrics) {
        // 例如: KiB Swap: 0 total, 0 free, 0 used. 3160192 avail Mem
        Pattern pattern = Pattern.compile("KiB Swap\\s*:\\s*(\\d+) total,\\s*(\\d+) free,\\s*(\\d+) used\\.?\\s*(?:([\\d]+) avail Mem)?");
        Matcher matcher = pattern.matcher(line.replaceAll("\\s+", " ")); // 标准化空格
        if (matcher.find()) {
            metrics.setTotalSwap(Long.parseLong(matcher.group(1))); // 总交换空间
            metrics.setFreeSwap(Long.parseLong(matcher.group(2))); // 空闲交换空间
            metrics.setUsedSwap(Long.parseLong(matcher.group(3))); // 已用交换空间

            // 如果有可用内存信息，则更新
            if (matcher.group(4) != null) {
                metrics.setAvailableMemory(Long.parseLong(matcher.group(4)));
            }
        }
    }

    /**
     * 解析磁盘使用率
     *
     * @param dfOutput df命令的输出
     * @return 磁盘使用率百分比
     */
    private double parseDiskUsage(String dfOutput) {
        if (dfOutput == null || dfOutput.isEmpty()) {
            return 0.0;
        }

        String[] lines = dfOutput.split("\n");
        double maxUsage = 0.0; // 使用最大使用率，而不是只看根目录

        for (String line : lines) {
            // 匹配磁盘使用情况，例如：/dev/sda1       20G   10G  9.0G  50% /
            if (line.trim().matches("^/dev/.*")) {
                // 使用正则表达式提取使用百分比
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b(\\d+)%");
                java.util.regex.Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    double usage = Double.parseDouble(matcher.group(1));
                    if (usage > maxUsage) {
                        maxUsage = usage;
                    }
                }
            }
        }
        return maxUsage;
    }

    private String executeCommand(String command) {
        // 实现命令执行逻辑
        try {
            return dataSourceProvider.executeCommand(command);
        } catch (Exception e) {
            log.error("Error executing command: {}", command, e);
            return "";
        }
    }

    /**
     * 执行命令并设置超时时间
     */
    private String executeCommandWithTimeout(String command, int timeoutMs) {
        // 创建一个Future任务来执行命令并在超时后取消
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return dataSourceProvider.executeCommand(command);
            } catch (Exception e) {
                log.error("Error executing command: {}", command, e);
                return "";
            }
        }, com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool());

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("Command execution timed out: {} ({}ms)", command, timeoutMs);
            return "";
        } catch (Exception e) {
            future.cancel(true);
            log.error("Command execution failed: {}", command, e);
            return "";
        }
    }

    /**
     * 解析uptime命令输出
     */
    private void parseUptimeOutput(String uptimeOutput, SystemMetrics metrics) {
        // 示例:  10:42:50 up 52 days, 20:01,  4 users,  load average: 0.15, 0.15, 0.13
        if (uptimeOutput != null) {
            String[] parts = uptimeOutput.trim().split(",\\s*load average:");
            if (parts.length >= 2) {
                // 解析系统时间和运行时间
                String timeAndUptime = parts[0];
                String[] timeParts = timeAndUptime.split("up");
                if (timeParts.length >= 2) {
                    // 提取时间戳和运行时间
                    String timePart = timeParts[0].trim();
                    String uptimePart = timeParts[1].trim();

                    // 提取时间戳（从右侧提取时间）
                    String[] timeFields = timePart.split("\\s+");
                    if (timeFields.length > 0) {
                        metrics.setTimestamp(timeFields[timeFields.length - 1]);
                    }
                    metrics.setUptime(uptimePart.replaceFirst("\\s*,.*$", "")); // 移除用户数部分

                    // 提取用户数
                    if (timeAndUptime.contains("users")) {
                        String[] userPart = timeAndUptime.split(",\\s*\\d+\\s+users");
                        String userStr = timeAndUptime.substring(userPart[0].length()).trim();
                        if (userStr.contains("users")) {
                            userStr = userStr.split("users")[0].trim();
                            userStr = userStr.replaceAll("[a-zA-Z\\s]+", "").trim();
                            try {
                                metrics.setUserCount(Integer.parseInt(userStr));
                            } catch (NumberFormatException e) {
                                // 忽略解析错误
                            }
                        }
                    }
                }

                // 解析负载平均值
                String loadavgPart = parts[1].trim();
                String[] loadavgValues = loadavgPart.split(",\\s*");
                if (loadavgValues.length >= 3) {
                    try {
                        metrics.setLoadAverage1(Double.parseDouble(loadavgValues[0]));
                        metrics.setLoadAverage5(Double.parseDouble(loadavgValues[1]));
                        metrics.setLoadAverage15(Double.parseDouble(loadavgValues[2]));
                    } catch (NumberFormatException e) {
                        // 忽略解析错误
                    }
                }
            }
        }
    }

    /**
     * 解析free命令输出
     */
    private void parseFreeOutput(String freeOutput, SystemMetrics metrics) {
        String[] lines = freeOutput.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("Mem:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 7) {
                    try {
                        metrics.setTotalMemory(Long.parseLong(parts[1]));
                        metrics.setUsedMemory(Long.parseLong(parts[2]));
                        metrics.setFreeMemory(Long.parseLong(parts[3]));
                        metrics.setBufferCacheMemory(Long.parseLong(parts[5])); // buff/cache
                        metrics.setAvailableMemory(Long.parseLong(parts[6])); // available
                    } catch (NumberFormatException e) {
                        // 忽略解析错误
                    }
                }
            } else if (line.startsWith("Swap:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    try {
                        metrics.setTotalSwap(Long.parseLong(parts[1]));
                        metrics.setUsedSwap(Long.parseLong(parts[2]));
                        metrics.setFreeSwap(Long.parseLong(parts[3]));
                    } catch (NumberFormatException e) {
                        // 忽略解析错误
                    }
                }
            }
        }
    }

    /**
     * 解析CPU统计信息
     */
    private void parseCpuStatOutput(String cpuStatOutput, SystemMetrics metrics) {
        if (cpuStatOutput != null && !cpuStatOutput.isEmpty()) {
            String[] parts = cpuStatOutput.trim().split("\\s+");
            if (parts.length >= 8) { // cpu user nice system idle iowait irq softirq [steal [guest ...]]
                try {
                    long user = Long.parseLong(parts[1]);
                    long nice = Long.parseLong(parts[2]);
                    long system = Long.parseLong(parts[3]);
                    long idle = Long.parseLong(parts[4]);
                    long iowait = Long.parseLong(parts[5]);
                    long irq = Long.parseLong(parts[6]);
                    long softirq = Long.parseLong(parts[7]);
                    // steal 字段在较新内核才有，旧内核可能缺失，此时按 0 处理
                    long steal = parts.length >= 9 ? Long.parseLong(parts[8]) : 0L;

                    long[] current = {user, nice, system, idle, iowait, irq, softirq, steal};
                    long[] prev = prevCpuFields;
                    prevCpuFields = current;
                    if (prev == null) {
                        // 首次采样只有累计计数：无法算速率，记录基线后交给后续采样（2026-10-23 审计 P1-16）
                        return;
                    }

                    // 两次采样间的增量百分比：直接用累计计数算出来的是"开机以来平均值"，几乎恒定
                    long dUser = user - prev[0], dNice = nice - prev[1], dSystem = system - prev[2];
                    long dIdle = idle - prev[3], dWait = iowait - prev[4];
                    long dIrq = irq - prev[5], dSoft = softirq - prev[6], dSteal = steal - prev[7];
                    long total = Math.max(1, dUser + dNice + dSystem + dIdle + dWait + dIrq + dSoft + dSteal);

                    metrics.setCpuUser((double) (dUser * 100) / total);
                    metrics.setCpuNice((double) (dNice * 100) / total);
                    metrics.setCpuSystem((double) (dSystem * 100) / total);
                    metrics.setCpuIdle((double) (dIdle * 100) / total);
                    metrics.setCpuWait((double) (dWait * 100) / total);
                    metrics.setCpuHardwareInterrupt((double) (dIrq * 100) / total);
                    metrics.setCpuSoftwareInterrupt((double) (dSoft * 100) / total);
                    metrics.setCpuSteal((double) (dSteal * 100) / total);
                } catch (NumberFormatException e) {
                    log.warn("Error parsing CPU stats: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 解析网络信息
     */
    private void parseNetworkOutput(String netOutput, SystemMetrics metrics) {
        if (netOutput == null || netOutput.isEmpty()) {
            return;
        }

        String[] lines = netOutput.split("\n");
        long totalRxBytes = 0;
        long totalTxBytes = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 跳过标题行与回环接口（此前远端 grep 的接口白名单会漏掉 ens33/eno1/enx 等主流命名）
            if (line.contains("Inter-|") || line.contains("|face")) continue;
            String ifname = line.split(":")[0].trim();
            if (ifname.equals("lo")) continue;

            // 解析网络接口信息：冒号后的 16 列依次是 rx_* 与 tx_*
            String[] parts = line.trim().split("[:\\s]+");
            if (parts.length >= 10) {
                try {
                    long rxBytes = Long.parseLong(parts[1]); // 接收字节数
                    long txBytes = Long.parseLong(parts[9]); // 发送字节数

                    totalRxBytes += rxBytes;
                    totalTxBytes += txBytes;
                } catch (NumberFormatException e) {
                    log.debug("Error parsing network stats: {}", e.getMessage());
                }
            }
        }

        // 累计总量与速率分开：netUp/netDown 的字段语义是"速度"，
        // 直接塞累计字节会把开机至今总流量显示成速率（2026-10-23 审计 P1-16）
        metrics.setTotalRxBytes(totalRxBytes);
        metrics.setTotalTxBytes(totalTxBytes);
        long now = System.currentTimeMillis();
        if (prevRxBytes >= 0 && prevTxBytes >= 0 && prevNetSampleTimeMs > 0) {
            double seconds = Math.max(0.001, (now - prevNetSampleTimeMs) / 1000.0);
            long rxDelta = Math.max(0, totalRxBytes - prevRxBytes);
            long txDelta = Math.max(0, totalTxBytes - prevTxBytes);
            metrics.setNetUp(formatNetworkSpeed((long) (txDelta / seconds)));
            metrics.setNetDown(formatNetworkSpeed((long) (rxDelta / seconds)));
        }
        prevRxBytes = totalRxBytes;
        prevTxBytes = totalTxBytes;
        prevNetSampleTimeMs = now;
    }

    /**
     * 格式化网络速度
     */
    private String formatNetworkSpeed(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 合并两个SystemMetrics对象
     */
    private void mergeMetrics(SystemMetrics base, SystemMetrics additional) {
        if (additional.getTimestamp() != null) base.setTimestamp(additional.getTimestamp());
        if (additional.getUptime() != null) base.setUptime(additional.getUptime());
        if (additional.getUserCount() > 0) base.setUserCount(additional.getUserCount());
        if (additional.getLoadAverage1() != 0.0) base.setLoadAverage1(additional.getLoadAverage1());
        if (additional.getLoadAverage5() != 0.0) base.setLoadAverage5(additional.getLoadAverage5());
        if (additional.getLoadAverage15() != 0.0) base.setLoadAverage15(additional.getLoadAverage15());
        if (additional.getTotalTasks() > 0) base.setTotalTasks(additional.getTotalTasks());
        if (additional.getRunningTasks() > 0) base.setRunningTasks(additional.getRunningTasks());
        if (additional.getSleepingTasks() > 0) base.setSleepingTasks(additional.getSleepingTasks());
        if (additional.getStoppedTasks() > 0) base.setStoppedTasks(additional.getStoppedTasks());
        if (additional.getZombieTasks() > 0) base.setZombieTasks(additional.getZombieTasks());
        if (additional.getCpuUser() != 0.0) base.setCpuUser(additional.getCpuUser());
        if (additional.getCpuSystem() != 0.0) base.setCpuSystem(additional.getCpuSystem());
        if (additional.getCpuNice() != 0.0) base.setCpuNice(additional.getCpuNice());
        if (additional.getCpuIdle() != 0.0) base.setCpuIdle(additional.getCpuIdle());
        if (additional.getCpuWait() != 0.0) base.setCpuWait(additional.getCpuWait());
        if (additional.getCpuHardwareInterrupt() != 0.0)
            base.setCpuHardwareInterrupt(additional.getCpuHardwareInterrupt());
        if (additional.getCpuSoftwareInterrupt() != 0.0)
            base.setCpuSoftwareInterrupt(additional.getCpuSoftwareInterrupt());
        if (additional.getCpuSteal() != 0.0) base.setCpuSteal(additional.getCpuSteal());
        if (additional.getTotalMemory() > 0) base.setTotalMemory(additional.getTotalMemory());
        if (additional.getFreeMemory() > 0) base.setFreeMemory(additional.getFreeMemory());
        if (additional.getUsedMemory() > 0) base.setUsedMemory(additional.getUsedMemory());
        if (additional.getBufferCacheMemory() > 0) base.setBufferCacheMemory(additional.getBufferCacheMemory());
        if (additional.getTotalSwap() > 0) base.setTotalSwap(additional.getTotalSwap());
        if (additional.getFreeSwap() > 0) base.setFreeSwap(additional.getFreeSwap());
        if (additional.getUsedSwap() > 0) base.setUsedSwap(additional.getUsedSwap());
        if (additional.getAvailableMemory() > 0) base.setAvailableMemory(additional.getAvailableMemory());
        if (additional.getNetUp() != null) base.setNetUp(additional.getNetUp());
        if (additional.getNetDown() != null) base.setNetDown(additional.getNetDown());
        if (additional.getTotalProcesses() > 0) base.setTotalProcesses(additional.getTotalProcesses());
        if (additional.getDiskReadBytes() > 0) base.setDiskReadBytes(additional.getDiskReadBytes());
        if (additional.getDiskWriteBytes() > 0) base.setDiskWriteBytes(additional.getDiskWriteBytes());
        if (additional.getTotalRxBytes() > 0) base.setTotalRxBytes(additional.getTotalRxBytes());
        if (additional.getTotalTxBytes() > 0) base.setTotalTxBytes(additional.getTotalTxBytes());
    }

    public synchronized void stopMonitoring() {
        monitoring = false;
        Thread thread = monitorThread;
        monitorThread = null;
        if (thread != null) thread.interrupt();
    }

    // 添加回调接口
    public interface MonitorCallback {
        // 使用 SystemMetrics 对象进行回调
        void onMetricsUpdate(SystemMetrics metrics);
    }

    public void setCallback(MonitorCallback callback) {
        this.callback = callback;
    }

    /**
     * 适配器类，将SSHConnectionManager适配到DataSourceProvider接口
     */
    private static class SSHConnectionManagerAdapter implements DataSourceProvider {
        private final SSHConnectionManager connectionManager;

        public SSHConnectionManagerAdapter(SSHConnectionManager connectionManager) {
            this.connectionManager = connectionManager;
        }

        @Override
        public String executeCommand(String command) throws Exception {
            return connectionManager.executeCommand(command);
        }

        @Override
        public boolean isConnected() {
            return connectionManager != null && connectionManager.isConnected();
        }
    }

    /**
     * 获取监控服务的状态信息
     */
    public Map<String, Object> getServiceStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("isMonitoring", monitoring);
        status.put("isConnected", dataSourceProvider.isConnected());
        status.put("hasCallback", callback != null);
        return status;
    }

    /**
     * 设置监控间隔
     */
    public void setMonitorInterval(long intervalMs) {
        // 由于AppConstants是静态的，我们不能直接修改它
        // 这里可以考虑使用局部变量或者系统属性来覆盖
        // 暂时保留原实现，可通过重启监控来应用新设置
    }

    /**
     * 获取数据源提供者
     */
    public DataSourceProvider getDataSourceProvider() {
        return dataSourceProvider;
    }

    /**
     * 设置数据源提供者
     */
    public void setDataSourceProvider(DataSourceProvider provider) {
        this.dataSourceProvider = provider;
    }
}
