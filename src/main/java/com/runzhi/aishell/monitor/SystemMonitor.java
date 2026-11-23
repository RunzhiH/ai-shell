/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.monitor.domain.MonitorSnapshot;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 系统监控外观：兼容旧 API（{@link MonitorCallback} + {@link SystemMetrics}），
 * 内部委托给连接级 {@link MonitorSession} 做采样调度。
 *
 * <p>历史职责：
 * <ul>
 *   <li>遗留同步路径 {@link #getSystemMetrics()}：按固定命令直接采样一轮，
 *       保留原有解析行为（含 top），供外部一次性调用与回归测试使用；</li>
 *   <li>调度路径 {@link #startMonitoring()}：使用会话的三档调度（快速/进程/低频），
 *       同时向新旧回调推送数据。</li>
 * </ul>
 */
@Slf4j
public class SystemMonitor {
    private DataSourceProvider dataSourceProvider;
    /** 关闭/切换监控后不再发起新命令；避免 SSH 已断开时把预期失败记成 ERROR。 */
    private volatile boolean stopping = false;
    /** 遗留同步路径的差分基线（调度路径的基线保存在 MonitorSession 的采集器里）。 */
    private long[] prevCpuFields;
    private long prevRxBytes = -1;
    private long prevTxBytes = -1;
    private long prevNetSampleTimeMs = 0;
    private MonitorCallback callback;
    /** 新视图订阅快照回调；与旧回调并行触发。 */
    private Consumer<MonitorSnapshot> snapshotCallback;
    /** 可配置的监控间隔；setMonitorInterval 此前是空方法，公共 API 静默忽略入参。 */
    private volatile long monitorIntervalMs = AppConstants.MONITOR_INTERVAL_MS;
    private volatile MonitorSession session;

    // 保留原有的构造函数以向后兼容
    public SystemMonitor(SSHConnectionManager connectionManager) {
        this.dataSourceProvider = new SSHConnectionManagerAdapter(connectionManager);
    }

    // 新的构造函数，接受数据源提供者
    public SystemMonitor(DataSourceProvider dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    /** 判断采样器是否仍绑定同一个 SSH 连接实例。 */
    public boolean isBoundTo(SSHConnectionManager connection) {
        return dataSourceProvider instanceof SSHConnectionManagerAdapter adapter
                && adapter.connectionManager == connection;
    }

    public synchronized void startMonitoring() {
        if (session != null && !session.isPausedOrStopped()) {
            return;
        }
        if (session != null) {
            session.stop();
        }
        stopping = false;
        session = new MonitorSession(dataSourceProvider, monitorIntervalMs);
        session.addListener(this::onSnapshot);
        session.start();
    }

    private void onSnapshot(MonitorSnapshot snapshot) {
        if (stopping) return;
        if (callback != null && snapshot.getSystemMetrics() != null) {
            callback.onMetricsUpdate(snapshot.getSystemMetrics());
        }
        if (snapshotCallback != null) {
            snapshotCallback.accept(snapshot);
        }
    }

    public synchronized void stopMonitoring() {
        stopping = true;
        if (session != null) {
            session.stop();
            session = null;
        }
    }

    /** 暂停采样与实时更新；恢复后重建差分基线，避免暂停区间形成速率突刺。 */
    public synchronized void pauseMonitoring() {
        if (session != null) session.pause();
    }

    public synchronized void resumeMonitoring() {
        if (session != null) {
            session.resume();
        } else if (dataSourceProvider != null && dataSourceProvider.isConnected()) {
            // 暂停后连接被替换的情况：重新建立会话
            startMonitoring();
        }
    }

    public synchronized boolean isMonitoringPaused() {
        return session != null && session.isPausedOrStopped();
    }

    /** 立即刷新：复用调度入口，不创建重叠采样任务。 */
    public void refreshNow() {
        if (session != null) session.triggerNow();
    }

    /**
     * 设置监控间隔：立即作用于正在运行的会话（重排快速通道调度）。
     * 速率计算始终使用实际采样时间差，间隔只控制调度。
     */
    public synchronized void setMonitorInterval(long intervalMs) {
        if (intervalMs < 500) {
            log.warn("Monitor interval {}ms is too small, clamped to {}", intervalMs, 500);
            intervalMs = 500;
        }
        this.monitorIntervalMs = intervalMs;
        if (session != null) {
            session.setIntervalMs(intervalMs);
        }
    }

    public long getMonitorInterval() {
        return monitorIntervalMs;
    }

    /** 进入进程页后启用进程列表采样；离开时关闭以节省开销。 */
    public void setProcessSamplingEnabled(boolean enabled) {
        if (session != null) session.setProcessSamplingEnabled(enabled);
    }

    public void setSnapshotCallback(Consumer<MonitorSnapshot> snapshotCallback) {
        this.snapshotCallback = snapshotCallback;
    }

    /**
     * 获取当前会话标识，界面据此丢弃切换主机后旧会话返回的数据。
     */
    public String getCurrentSessionId() {
        return session != null ? session.getSessionId() : null;
    }

    /**
     * 获取系统指标并解析为 SystemMetrics 对象（遗留同步路径）。
     *
     * <p>该路径保留原有的固定命令与解析行为（含 top 解析），供外部一次性调用与回归测试使用；
     * 生产界面数据走 {@link MonitorSession} 的三档调度。
     *
     * @return SystemMetrics 对象
     */
    public SystemMetrics getSystemMetrics() {
        if (stopping) return null;
        try {
            SystemMetrics metrics = SystemMetrics.builder().build();

            String dfOutput = executeCommandWithTimeout("df -h", AppConstants.DF_COMMAND_TIMEOUT);
            if (dfOutput != null && !dfOutput.isEmpty()) {
                double diskUsage = parseDiskUsage(dfOutput);
                metrics.setDiskUsage(diskUsage);
                metrics.setDiskUsageString(String.format("%.1f%%", diskUsage));
            }

            String uptimeOutput = executeCommandWithTimeout("uptime", AppConstants.UPTIME_COMMAND_TIMEOUT);
            if (uptimeOutput != null && !uptimeOutput.isEmpty()) {
                parseUptimeOutput(uptimeOutput, metrics);
            }

            String memOutput = executeCommandWithTimeout("free -k", AppConstants.FREE_COMMAND_TIMEOUT);
            if (memOutput != null && !memOutput.isEmpty()) {
                parseFreeOutput(memOutput, metrics);
            }

            String cpuStatOutput = executeCommandWithTimeout("cat /proc/stat | head -n 1", AppConstants.CPUSTAT_COMMAND_TIMEOUT);
            if (cpuStatOutput != null && !cpuStatOutput.isEmpty()) {
                parseCpuStatOutput(cpuStatOutput, metrics);
            }

            String netOutput = executeCommandWithTimeout("cat /proc/net/dev", AppConstants.NET_COMMAND_TIMEOUT);
            if (netOutput != null && !netOutput.isEmpty()) {
                parseNetworkOutput(netOutput, metrics);
            }

            String psOutput = executeCommandWithTimeout("ps aux --no-headers | wc -l", AppConstants.PROCESS_COMMAND_TIMEOUT);
            if (psOutput != null && !psOutput.isEmpty()) {
                try {
                    int processCount = Integer.parseInt(psOutput.trim());
                    metrics.setTotalProcesses(processCount);
                } catch (NumberFormatException e) {
                    log.warn("Could not parse process count: {}", psOutput);
                }
            }

            // 磁盘 I/O：完整采集设备统计（此前 head -n 5 会漏掉其余设备）
            String diskIoOutput = executeCommandWithTimeout("cat /proc/diskstats 2>/dev/null", AppConstants.PROCESS_COMMAND_TIMEOUT);
            if (diskIoOutput != null && !diskIoOutput.isEmpty()) {
                parseDiskIoOutput(diskIoOutput, metrics);
            }

            try {
                String topOutput = executeCommandWithTimeout(
                        "export LANG=\"en_US\"; export LANGUAGE=\"en_US\"; export LC_ALL=\"en_US\"; timeout 15 top -b -n 1",
                        AppConstants.TOP_COMMAND_TIMEOUT
                );
                SystemMetrics topMetrics = parseTopOutput(topOutput);
                if (topMetrics != null) {
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

        String[] lines = diskIoOutput.split("\\R");
        long totalRead = 0;
        long totalWrite = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 14) {
                try {
                    long sectorsRead = Long.parseLong(fields[5]);
                    long sectorsWritten = Long.parseLong(fields[9]);

                    totalRead += sectorsRead;
                    totalWrite += sectorsWritten;
                } catch (NumberFormatException e) {
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
     */
    private SystemMetrics parseTopOutput(String topOutput) {
        if (topOutput == null || topOutput.isEmpty()) {
            return null;
        }

        SystemMetrics metrics = SystemMetrics.builder().build();
        String[] lines = topOutput.split("\\R");

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("top - ")) {
                parseTimestampAndUptime(line, metrics);
            } else if (line.startsWith("Tasks:")) {
                parseTasks(line, metrics);
            } else if (line.startsWith("%Cpu(s):")) {
                parseCpu(line, metrics);
            } else if (line.contains("KiB Mem")) {
                parseMemory(line, metrics);
            } else if (line.contains("KiB Swap")) {
                parseSwap(line, metrics);
            }
        }

        return metrics;
    }

    private void parseTimestampAndUptime(String line, SystemMetrics metrics) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("top - (\\d{2}:\\d{2}:\\d{2}) up ([^,]+),\\s*(\\d+) users?,\\s*load average:\\s*([\\d.]+),\\s*([\\d.]+),\\s*([\\d.]+)");
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            metrics.setTimestamp(matcher.group(1));
            metrics.setUptime(matcher.group(2));
            metrics.setUserCount(Integer.parseInt(matcher.group(3)));
            metrics.setLoadAverage1(Double.parseDouble(matcher.group(4)));
            metrics.setLoadAverage5(Double.parseDouble(matcher.group(5)));
            metrics.setLoadAverage15(Double.parseDouble(matcher.group(6)));
        }
    }

    private void parseTasks(String line, SystemMetrics metrics) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Tasks:\\s*(\\d+) total,\\s*(\\d+) running,\\s*(\\d+) sleeping,\\s*(\\d+) stopped,\\s*(\\d+) zombie");
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            metrics.setTotalTasks(Integer.parseInt(matcher.group(1)));
            metrics.setRunningTasks(Integer.parseInt(matcher.group(2)));
            metrics.setSleepingTasks(Integer.parseInt(matcher.group(3)));
            metrics.setStoppedTasks(Integer.parseInt(matcher.group(4)));
            metrics.setZombieTasks(Integer.parseInt(matcher.group(5)));
        }
    }

    private void parseCpu(String line, SystemMetrics metrics) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("%Cpu\\(s\\):\\s*([\\d.]+) us,\\s*([\\d.]+) sy,\\s*([\\d.]+) ni,\\s*([\\d.]+) id,\\s*([\\d.]+) wa,\\s*([\\d.]+) hi,\\s*([\\d.]+) si,\\s*([\\d.]+) st");
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            metrics.setCpuUser(Double.parseDouble(matcher.group(1)));
            metrics.setCpuSystem(Double.parseDouble(matcher.group(2)));
            metrics.setCpuNice(Double.parseDouble(matcher.group(3)));
            metrics.setCpuIdle(Double.parseDouble(matcher.group(4)));
            metrics.setCpuWait(Double.parseDouble(matcher.group(5)));
            metrics.setCpuHardwareInterrupt(Double.parseDouble(matcher.group(6)));
            metrics.setCpuSoftwareInterrupt(Double.parseDouble(matcher.group(7)));
            metrics.setCpuSteal(Double.parseDouble(matcher.group(8)));
        }
    }

    private void parseMemory(String line, SystemMetrics metrics) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("KiB Mem\\s*:\\s*(\\d+) total,\\s*(\\d+) free,\\s*(\\d+) used,\\s*(\\d+) buff/cache");
        java.util.regex.Matcher matcher = pattern.matcher(line.replaceAll("\\s+", " "));
        if (matcher.find()) {
            metrics.setTotalMemory(Long.parseLong(matcher.group(1)));
            metrics.setFreeMemory(Long.parseLong(matcher.group(2)));
            metrics.setUsedMemory(Long.parseLong(matcher.group(3)));
            metrics.setBufferCacheMemory(Long.parseLong(matcher.group(4)));
        }

        if (metrics.getAvailableMemory() == 0 && metrics.getFreeMemory() > 0 && metrics.getBufferCacheMemory() > 0) {
            metrics.setAvailableMemory(metrics.getFreeMemory() + metrics.getBufferCacheMemory());
        }
    }

    private void parseSwap(String line, SystemMetrics metrics) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("KiB Swap\\s*:\\s*(\\d+) total,\\s*(\\d+) free,\\s*(\\d+) used\\.?\\s*(?:([\\d]+) avail Mem)?");
        java.util.regex.Matcher matcher = pattern.matcher(line.replaceAll("\\s+", " "));
        if (matcher.find()) {
            metrics.setTotalSwap(Long.parseLong(matcher.group(1)));
            metrics.setFreeSwap(Long.parseLong(matcher.group(2)));
            metrics.setUsedSwap(Long.parseLong(matcher.group(3)));

            if (matcher.group(4) != null) {
                metrics.setAvailableMemory(Long.parseLong(matcher.group(4)));
            }
        }
    }

    /**
     * 解析磁盘使用率
     */
    private double parseDiskUsage(String dfOutput) {
        if (dfOutput == null || dfOutput.isEmpty()) {
            return 0.0;
        }

        String[] lines = dfOutput.split("\\R");
        double maxUsage = 0.0;

        for (String line : lines) {
            if (line.trim().matches("^/dev/.*")) {
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

    /** 连接已关闭或监控正在停止时，命令失败属于预期，不应污染 ERROR 日志。 */
    private void logCommandFailure(String command, Exception failure) {
        if (stopping || !isDataSourceConnected()) {
            log.debug("System monitor command stopped with session: {}", failure.getMessage());
        } else {
            log.error("Error executing command: {}", command, failure);
        }
    }

    private boolean isDataSourceConnected() {
        try {
            return dataSourceProvider != null && dataSourceProvider.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 执行命令并设置超时时间
     */
    private String executeCommandWithTimeout(String command, int timeoutMs) {
        if (stopping) return "";
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return dataSourceProvider.executeCommand(command);
            } catch (Exception e) {
                logCommandFailure(command, e);
                return "";
            }
        }, com.runzhi.aishell.common.util.ThreadPoolManager.getInstance().getIoThreadPool());

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("Command execution timed out: {} ({}ms)", command, timeoutMs);
            return "";
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.debug("Command wait interrupted while stopping: {}", command);
            return "";
        } catch (Exception e) {
            future.cancel(true);
            logCommandFailure(command, e);
            return "";
        }
    }

    /**
     * 解析uptime命令输出
     */
    private void parseUptimeOutput(String uptimeOutput, SystemMetrics metrics) {
        if (uptimeOutput != null) {
            String[] parts = uptimeOutput.trim().split(",\\s*load average:");
            if (parts.length >= 2) {
                String timeAndUptime = parts[0];
                String[] timeParts = timeAndUptime.split("up");
                if (timeParts.length >= 2) {
                    String timePart = timeParts[0].trim();
                    String uptimePart = timeParts[1].trim();

                    String[] timeFields = timePart.split("\\s+");
                    if (timeFields.length > 0) {
                        metrics.setTimestamp(timeFields[timeFields.length - 1]);
                    }
                    metrics.setUptime(uptimePart.replaceFirst("\\s*,.*$", ""));

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
        String[] lines = freeOutput.split("\\R");

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("Mem:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 7) {
                    try {
                        metrics.setTotalMemory(Long.parseLong(parts[1]));
                        metrics.setUsedMemory(Long.parseLong(parts[2]));
                        metrics.setFreeMemory(Long.parseLong(parts[3]));
                        metrics.setBufferCacheMemory(Long.parseLong(parts[5]));
                        metrics.setAvailableMemory(Long.parseLong(parts[6]));
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
            if (parts.length >= 8) {
                try {
                    long user = Long.parseLong(parts[1]);
                    long nice = Long.parseLong(parts[2]);
                    long system = Long.parseLong(parts[3]);
                    long idle = Long.parseLong(parts[4]);
                    long iowait = Long.parseLong(parts[5]);
                    long irq = Long.parseLong(parts[6]);
                    long softirq = Long.parseLong(parts[7]);
                    long steal = parts.length >= 9 ? Long.parseLong(parts[8]) : 0L;

                    long[] current = {user, nice, system, idle, iowait, irq, softirq, steal};
                    long[] prev = prevCpuFields;
                    prevCpuFields = current;
                    if (prev == null) {
                        return;
                    }

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

        String[] lines = netOutput.split("\\R");
        long totalRxBytes = 0;
        long totalTxBytes = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            int colon = line.indexOf(':');
            if (colon <= 0) continue;

            String ifname = line.substring(0, colon).trim();
            if (ifname.isEmpty() || ifname.indexOf('|') >= 0
                    || ifname.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            if ("lo".equals(ifname)) continue;

            String[] parts = line.substring(colon + 1).trim().split("\\s+");
            if (parts.length < 16) continue;

            try {
                long rxBytes = Long.parseLong(parts[0]);
                long txBytes = Long.parseLong(parts[8]);

                totalRxBytes += rxBytes;
                totalTxBytes += txBytes;
            } catch (NumberFormatException e) {
                log.debug("Error parsing network stats for {}: {}", ifname, e.getMessage());
            }
        }

        // 累计总量与速率分开：netUp/netDown 的字段语义是"速度"
        metrics.setTotalRxBytes(totalRxBytes);
        metrics.setTotalTxBytes(totalTxBytes);
        long now = System.currentTimeMillis();
        if (prevRxBytes >= 0 && prevTxBytes >= 0 && prevNetSampleTimeMs > 0) {
            double seconds = Math.max(0.001, (now - prevNetSampleTimeMs) / 1000.0);
            long rxDelta = Math.max(0, totalRxBytes - prevRxBytes);
            long txDelta = Math.max(0, totalTxBytes - prevTxBytes);
            metrics.setNetUp(MonitorFormats.legacyNetworkSpeed((long) (txDelta / seconds)));
            metrics.setNetDown(MonitorFormats.legacyNetworkSpeed((long) (rxDelta / seconds)));
        }
        prevRxBytes = totalRxBytes;
        prevTxBytes = totalTxBytes;
        prevNetSampleTimeMs = now;
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

    // 添加回调接口
    public interface MonitorCallback {
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
        status.put("isMonitoring", session != null);
        status.put("isConnected", isDataSourceConnected());
        status.put("hasCallback", callback != null);
        status.put("intervalMs", monitorIntervalMs);
        return status;
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
