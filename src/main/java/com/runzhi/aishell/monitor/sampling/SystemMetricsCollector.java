/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.sampling;

import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.monitor.CommandResult;
import com.runzhi.aishell.monitor.CommandRunner;
import com.runzhi.aishell.monitor.domain.MountInfo;
import com.runzhi.aishell.monitor.domain.MountsSample;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.monitor.domain.SystemSample;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统级采集器：uptime、free、/proc/stat 与 df 合并为一次轻量采样。
 *
 * <p>CPU 使用率基于相邻两次 /proc/stat 的增量计算；首次采样只建立基线。
 * 采集器实例与会话同生命周期，切换主机/重连后基线自然重建。
 */
@Slf4j
public class SystemMetricsCollector {

    private static final String UPTIME_COMMAND = "uptime";
    private static final String FREE_COMMAND = "free -k";
    private static final String CPU_STAT_COMMAND = "cat /proc/stat | head -n 1";
    /** POSIX 1KiB 块输出，数值稳定且与区域设置无关。 */
    static final String DF_COMMAND = "LC_ALL=C df -Pk";

    /** CPU /proc/stat 上次采样的各字段累计值。 */
    private long[] prevCpuFields;

    private final CommandRunner runner;

    public SystemMetricsCollector(CommandRunner runner) {
        this.runner = runner;
    }

    /** 清空差分基线：恢复采样与重建会话时调用。 */
    public void resetBaseline() {
        prevCpuFields = null;
    }

    /**
     * 快速通道采样：uptime、free、/proc/stat。文件系统容量属于低频数据，
     * 由 {@link #collectMounts()} 单独按 30 秒间隔刷新。
     */
    public SystemSample collect() {
        SystemMetrics metrics = SystemMetrics.builder().build();
        boolean cpuFirstSample = false;

        CommandResult uptimeResult = runner.run(UPTIME_COMMAND, AppConstants.UPTIME_COMMAND_TIMEOUT);
        if (uptimeResult.hasOutput()) {
            parseUptimeOutput(uptimeResult.getOutput(), metrics);
        }

        CommandResult freeResult = runner.run(FREE_COMMAND, AppConstants.FREE_COMMAND_TIMEOUT);
        if (freeResult.hasOutput()) {
            parseFreeOutput(freeResult.getOutput(), metrics);
        }

        CommandResult cpuResult = runner.run(CPU_STAT_COMMAND, AppConstants.CPUSTAT_COMMAND_TIMEOUT);
        if (cpuResult.hasOutput()) {
            cpuFirstSample = parseCpuStatOutput(cpuResult.getOutput(), metrics);
        }

        return SystemSample.builder()
                .systemMetrics(metrics)
                .cpuFirstSample(cpuFirstSample)
                .mounts(List.of())
                .mountsAvailable(false)
                .build();
    }

    /** 低频通道：文件系统容量（df -Pk）。 */
    public MountsSample collectMounts() {
        List<MountInfo> mounts = new ArrayList<>();
        CommandResult dfResult = runner.run(DF_COMMAND, AppConstants.DF_COMMAND_TIMEOUT);
        if (!dfResult.hasOutput()) {
            return MountsSample.empty(dfResult.isTimedOut() ? "容量采样超时" : "无法执行 df 命令");
        }
        SystemMetrics metrics = SystemMetrics.builder().build();
        boolean found = parseDfOutput(dfResult.getOutput(), metrics, mounts);
        if (!found) {
            return MountsSample.empty("df 输出没有可解析的挂载条目");
        }
        return MountsSample.ok(mounts, metrics.getDiskUsage());
    }

    private void parseUptimeOutput(String uptimeOutput, SystemMetrics metrics) {
        String trimmed = uptimeOutput.trim();
        // 示例:  10:42:50 up 52 days, 20:01,  4 users,  load average: 0.15, 0.15, 0.13
        int loadIndex = trimmed.indexOf("load average:");
        if (loadIndex < 0) return;
        String timeAndUptime = trimmed.substring(0, loadIndex).trim();

        Matcher matcher = Pattern.compile("^(.*?)\\s+up\\s+(.*)$").matcher(timeAndUptime);
        if (matcher.find()) {
            String timePart = matcher.group(1).trim();
            String uptimePart = matcher.group(2).trim();
            String[] timeFields = timePart.split("\\s+");
            if (timeFields.length > 0) {
                metrics.setTimestamp(timeFields[timeFields.length - 1]);
            }
            metrics.setUptime(uptimePart.replaceFirst("\\s*,\\s*\\d+\\s+users?.*$", ""));

            Matcher userMatcher = Pattern.compile("(\\d+)\\s+users?").matcher(uptimePart);
            if (userMatcher.find()) {
                metrics.setUserCount(Integer.parseInt(userMatcher.group(1)));
            }
        }

        String loadavgPart = trimmed.substring(loadIndex + "load average:".length()).trim();
        String[] loadavgValues = loadavgPart.split(",\\s*");
        if (loadavgValues.length >= 3) {
            try {
                metrics.setLoadAverage1(Double.parseDouble(loadavgValues[0]));
                metrics.setLoadAverage5(Double.parseDouble(loadavgValues[1]));
                metrics.setLoadAverage15(Double.parseDouble(loadavgValues[2]));
            } catch (NumberFormatException e) {
                log.debug("Could not parse load average: {}", loadavgPart);
            }
        }
    }

    private void parseFreeOutput(String freeOutput, SystemMetrics metrics) {
        for (String rawLine : freeOutput.split("\\R")) {
            String line = rawLine.trim();
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
                        log.debug("Could not parse Mem line: {}", line);
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
                        log.debug("Could not parse Swap line: {}", line);
                    }
                }
            }
        }
    }

    /**
     * @return 是否为首次采样（只建立了基线）
     */
    private boolean parseCpuStatOutput(String cpuStatOutput, SystemMetrics metrics) {
        String[] parts = cpuStatOutput.trim().split("\\s+");
        if (parts.length < 8) return false;
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
                // 首次采样只有累计计数：记录基线后交给后续采样
                return true;
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
            // 单核口径下的总使用率：user+nice+system+irq+softirq+steal+iowait，恒等于 100-idle
            metrics.setCpuTotalUsed(Math.max(0.0, 100.0 - metrics.getCpuIdle()));
        } catch (NumberFormatException e) {
            log.warn("Error parsing CPU stats: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 解析 df -Pk 输出，填充文件系统容量列表与总体使用率。
     *
     * @return 是否解析到至少一个挂载条目
     */
    static boolean parseDfOutput(String dfOutput, SystemMetrics metrics, List<MountInfo> outMounts) {
        double maxUsage = 0.0;
        boolean found = false;
        for (String rawLine : dfOutput.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("Filesystem")) continue;
            // 挂载点可能含空格：split 至多 6 段，最后一段保留完整路径
            String[] fields = line.split("\\s+", 6);
            if (fields.length < 6) continue;
            try {
                long totalKiB = Long.parseLong(fields[1]);
                long usedKiB = Long.parseLong(fields[2]);
                long availableKiB = Long.parseLong(fields[3]);
                if (totalKiB <= 0) continue;
                double usagePercent = parseCapacityPercent(fields[4], usedKiB, totalKiB);
                outMounts.add(MountInfo.builder()
                        .device(fields[0])
                        .mountPoint(fields[5])
                        .totalKiB(totalKiB)
                        .usedKiB(usedKiB)
                        .availableKiB(availableKiB)
                        .usagePercent(usagePercent)
                        .build());
                found = true;
                maxUsage = Math.max(maxUsage, usagePercent);
            } catch (NumberFormatException e) {
                log.debug("Skipping df line: {}", line);
            }
        }
        metrics.setDiskUsage(maxUsage);
        metrics.setDiskUsageString(String.format("%.1f%%", maxUsage));
        return found;
    }

    private static double parseCapacityPercent(String capacityField, long usedKiB, long totalKiB) {
        if (capacityField != null) {
            String digits = capacityField.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return Double.parseDouble(digits);
                } catch (NumberFormatException ignored) {
                    // 回退到由 used/total 计算
                }
            }
        }
        return totalKiB > 0 ? (double) usedKiB * 100.0 / totalKiB : 0.0;
    }

    /** 由挂载列表推导磁盘总体使用率（保留给未走 df 路径的调用方）。 */
    public static double maxUsageOf(List<MountInfo> mounts) {
        return mounts.stream().mapToDouble(MountInfo::getUsagePercent).max().orElse(0.0);
    }
}
