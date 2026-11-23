/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.sampling;

import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.monitor.CommandResult;
import com.runzhi.aishell.monitor.CommandRunner;
import com.runzhi.aishell.monitor.domain.DiskIoRate;
import com.runzhi.aishell.monitor.domain.DiskIoStats;
import com.runzhi.aishell.monitor.domain.MountInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 磁盘 I/O 采集器：完整读取 /proc/diskstats，按设备维护前后两次快照，
 * 用实际采样时间差计算吞吐、IOPS、平均请求耗时与繁忙时间占比。
 *
 * <p>规则（方案 §4）：
 * <ul>
 *   <li>设备重新出现或计数器下降时重建基线，不输出负速率或异常突刺；</li>
 *   <li>平均请求耗时依赖操作增量，没有操作时为 null（界面显示 —）；</li>
 *   <li>设备、分区、逻辑卷分层展示；扇区字节大小来自 sysfs（默认 512）。</li>
 * </ul>
 */
@Slf4j
public class DiskIoCollector {

    public static final String DISKSTATS_COMMAND = "cat /proc/diskstats 2>/dev/null";
    /** 内核文档 iostats.rst：扇区计数按 512 字节换算；4K 原生设备由 sysfs 覆盖。 */
    static final int DEFAULT_SECTOR_SIZE = 512;

    private final CommandRunner runner;
    private final LongSupplier nanoClock;

    /** 上一次采样的设备计数。 */
    private final Map<String, DiskIoStats> prevStats = new LinkedHashMap<>();
    /** 设备扇区字节大小（sysfs 读取，低频刷新）。 */
    private final Map<String, Integer> sectorSizes = new HashMap<>();
    /** 已采样的设备集合：用于识别「重新出现」。 */
    private final java.util.Set<String> knownDevices = new java.util.HashSet<>();

    private boolean firstSample = true;

    public DiskIoCollector(CommandRunner runner) {
        this(runner, System::nanoTime);
    }

    public DiskIoCollector(CommandRunner runner, LongSupplier nanoClock) {
        this.runner = runner;
        this.nanoClock = nanoClock;
    }

    public void resetBaseline() {
        prevStats.clear();
        knownDevices.clear();
        firstSample = true;
    }

    /**
     * 刷新设备扇区字节大小（低频调用）。设备名来自此前 diskstats 结果，均为内核生成，
     * 不含空白，可安全用于预定义的 sysfs 遍历命令。
     */
    public void refreshSectorSizes(java.util.Collection<String> devices) {
        if (devices.isEmpty()) return;
        StringBuilder command = new StringBuilder("for d in");
        for (String device : devices) {
            command.append(' ').append(device);
        }
        command.append("; do printf '%s=%s\n' $d $(cat /sys/block/$d/queue/hw_sector_size 2>/dev/null); done");
        CommandResult result = runner.run(command.toString(), AppConstants.DF_COMMAND_TIMEOUT);
        if (!result.hasOutput()) return;
        for (String line : result.getOutput().split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String device = line.substring(0, eq).trim();
            String sizeText = line.substring(eq + 1).trim();
            try {
                if (!sizeText.isEmpty()) {
                    sectorSizes.put(device, Integer.parseInt(sizeText));
                }
            } catch (NumberFormatException e) {
                log.debug("Could not parse sector size for {}: {}", device, sizeText);
            }
        }
    }

    /**
     * 采集一轮设备统计并计算速率。
     *
     * @param mounts 文件系统挂载信息，用于关联挂载点（可为空）
     * @return 按设备名索引的速率；首次采样只建立基线，返回空表
     */
    public Result collect(List<MountInfo> mounts) {
        CommandResult result = runner.run(DISKSTATS_COMMAND, AppConstants.PROCESS_COMMAND_TIMEOUT);
        if (!result.hasOutput()) {
            return Result.empty(result.isTimedOut()
                    ? "磁盘 I/O 采样超时" : "无法读取 /proc/diskstats");
        }

        long capturedNanos = nanoClock.getAsLong();
        Map<String, DiskIoStats> current = parseDiskStats(result.getOutput(), capturedNanos);
        Map<String, String> mountByDevice = mountByDevice(mounts);

        Map<String, DiskIoRate> rates = new LinkedHashMap<>();
        long totalReadBytes = 0L;
        long totalWriteBytes = 0L;

        if (firstSample) {
            prevStats.putAll(current);
            knownDevices.addAll(current.keySet());
            firstSample = false;
            return Result.firstSample();
        }

        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, DiskIoStats> entry : current.entrySet()) {
            String device = entry.getKey();
            DiskIoStats cur = entry.getValue();
            DiskIoStats prev = prevStats.get(device);

            int sectorSize = sectorSizes.getOrDefault(device, DEFAULT_SECTOR_SIZE);
            long readBytes = cur.getSectorsRead() * sectorSize;
            long writeBytes = cur.getSectorsWritten() * sectorSize;

            DiskIoRate rate;
            if (prev == null) {
                // 新出现设备：本轮只建基线
                rate = DiskIoRate.builder()
                        .device(device)
                        .totalReadBytes(readBytes)
                        .totalWriteBytes(writeBytes)
                        .deviceLevel(classifyDeviceLevel(device))
                        .mountPoint(mountByDevice.get(device))
                        .state(com.runzhi.aishell.monitor.domain.SampleState.FIRST_SAMPLE)
                        .reason("设备首次出现，建立基线")
                        .build();
            } else {
                double seconds = Math.max(0.001, (cur.getCapturedNanos() - prev.getCapturedNanos()) / 1_000_000_000.0);
                boolean counterReset = cur.getSectorsRead() < prev.getSectorsRead()
                        || cur.getSectorsWritten() < prev.getSectorsWritten()
                        || cur.getReadsCompleted() < prev.getReadsCompleted()
                        || cur.getWritesCompleted() < prev.getWritesCompleted();
                boolean reappeared = !knownDevices.contains(device);
                if (counterReset || reappeared) {
                    // 计数器下降或设备曾消失：重建基线，本轮不输出速率
                    rate = DiskIoRate.builder()
                            .device(device)
                            .totalReadBytes(readBytes)
                            .totalWriteBytes(writeBytes)
                            .deviceLevel(classifyDeviceLevel(device))
                            .mountPoint(mountByDevice.get(device))
                            .state(com.runzhi.aishell.monitor.domain.SampleState.FIRST_SAMPLE)
                            .reason(counterReset ? "计数器变化，重建基线" : "设备重新出现，重建基线")
                            .build();
                } else {
                    double readBytesPerSec = (cur.getSectorsRead() - prev.getSectorsRead()) * sectorSize / seconds;
                    double writeBytesPerSec = (cur.getSectorsWritten() - prev.getSectorsWritten()) * sectorSize / seconds;
                    double readIops = (cur.getReadsCompleted() - prev.getReadsCompleted()) / seconds;
                    double writeIops = (cur.getWritesCompleted() - prev.getWritesCompleted()) / seconds;

                    long ioOpsDelta = (cur.getReadsCompleted() - prev.getReadsCompleted())
                            + (cur.getWritesCompleted() - prev.getWritesCompleted());
                    // 平均请求耗时依赖操作增量；没有操作时不给数值
                    Double avgRequestMs = null;
                    if (ioOpsDelta > 0) {
                        long weightedDelta = cur.getWeightedIoTimeMs() - prev.getWeightedIoTimeMs();
                        avgRequestMs = weightedDelta / (double) ioOpsDelta;
                    }

                    double busyPercent = 0.0;
                    long ioTimeDelta = cur.getIoTimeMs() - prev.getIoTimeMs();
                    if (ioTimeDelta > 0) {
                        busyPercent = Math.min(100.0, ioTimeDelta / (seconds * 1000.0) * 100.0);
                    }

                    rate = DiskIoRate.builder()
                            .device(device)
                            .readBytesPerSec(readBytesPerSec)
                            .writeBytesPerSec(writeBytesPerSec)
                            .readIops(readIops)
                            .writeIops(writeIops)
                            .avgRequestMs(avgRequestMs)
                            .busyPercent(busyPercent)
                            .totalReadBytes(readBytes)
                            .totalWriteBytes(writeBytes)
                            .deviceLevel(classifyDeviceLevel(device))
                            .mountPoint(mountByDevice.get(device))
                            .state(com.runzhi.aishell.monitor.domain.SampleState.OK)
                            .build();
                }
            }
            rates.put(device, rate);

            // 累计读写字节：仅按物理设备口径合计，避免分区与设备重复累计
            if (isPhysicalDeviceLevel(rate.getDeviceLevel())) {
                totalReadBytes += readBytes;
                totalWriteBytes += writeBytes;
            }
        }

        // 消失的设备：移出基线，不再输出
        for (String device : new ArrayList<>(prevStats.keySet())) {
            if (!current.containsKey(device)) gone.add(device);
        }
        for (String device : gone) {
            prevStats.remove(device);
            knownDevices.remove(device);
        }
        prevStats.keySet().retainAll(current.keySet());
        for (String device : current.keySet()) {
            prevStats.put(device, current.get(device));
        }
        knownDevices.addAll(current.keySet());

        return Result.ok(rates, totalReadBytes, totalWriteBytes);
    }

    /** 解析 /proc/diskstats 的全部设备行。 */
    static Map<String, DiskIoStats> parseDiskStats(String output, long capturedNanos) {
        Map<String, DiskIoStats> stats = new LinkedHashMap<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\s+");
            if (fields.length < 14) continue;
            try {
                DiskIoStats statsEntry = DiskIoStats.builder()
                        .device(fields[2])
                        .readsCompleted(Long.parseLong(fields[3]))
                        .readsMerged(Long.parseLong(fields[4]))
                        .sectorsRead(Long.parseLong(fields[5]))
                        .timeReadingMs(Long.parseLong(fields[6]))
                        .writesCompleted(Long.parseLong(fields[7]))
                        .writesMerged(Long.parseLong(fields[8]))
                        .sectorsWritten(Long.parseLong(fields[9]))
                        .timeWritingMs(Long.parseLong(fields[10]))
                        .iosInProgress(Long.parseLong(fields[11]))
                        .ioTimeMs(Long.parseLong(fields[12]))
                        .weightedIoTimeMs(Long.parseLong(fields[13]))
                        .capturedNanos(capturedNanos)
                        .build();
                if (!statsEntry.getDevice().isEmpty()) {
                    stats.put(statsEntry.getDevice(), statsEntry);
                }
            } catch (NumberFormatException e) {
                log.debug("Could not parse diskstats line: {}", line);
            }
        }
        return stats;
    }

    /** 设备层级：物理设备 / 分区 / 逻辑卷 / 虚拟设备。 */
    public static String classifyDeviceLevel(String device) {
        if (device == null || device.isEmpty()) return "未知";
        if (device.startsWith("dm-")) return "逻辑卷";
        if (device.startsWith("loop") || device.startsWith("ram") || device.startsWith("zram")
                || device.startsWith("sr") || device.startsWith("md")) {
            return "虚拟/镜像设备";
        }
        // NVMe：nvme0n1 为物理设备，nvme0n1p2 为其分区
        if (device.matches("^nvme\\d+n\\d+p\\d+$")) return "分区";
        if (device.matches("^nvme\\d+n\\d+$")) return "物理设备";
        // sda / vdb / xvdc 为物理设备，sda1 / vdb3 为分区
        if (device.matches("^(sd|vd|xv|hd)[a-z]+$")) return "物理设备";
        if (device.matches("^(sd|vd|xv|hd)[a-z]+\\d+$")) return "分区";
        return "其他";
    }

    public static boolean isPhysicalDeviceLevel(String level) {
        return "物理设备".equals(level);
    }

    private static Map<String, String> mountByDevice(List<MountInfo> mounts) {
        Map<String, String> mapping = new HashMap<>();
        if (mounts == null) return mapping;
        for (MountInfo mount : mounts) {
            if (mount.getDevice() == null || mount.getMountPoint() == null) continue;
            String basename = mount.getDevice();
            int slash = basename.lastIndexOf('/');
            if (slash >= 0) basename = basename.substring(slash + 1);
            if (!basename.isEmpty()) {
                mapping.putIfAbsent(basename, mount.getMountPoint());
            }
        }
        return mapping;
    }

    /** 采集结果：速率表 + 供旧指标使用的合计读写字节。 */
    public static class Result {
        public final Map<String, DiskIoRate> rates;
        public final long totalReadBytes;
        public final long totalWriteBytes;
        public final boolean firstSample;
        public final String failureReason;

        private Result(Map<String, DiskIoRate> rates, long totalReadBytes, long totalWriteBytes,
                       boolean firstSample, String failureReason) {
            this.rates = rates;
            this.totalReadBytes = totalReadBytes;
            this.totalWriteBytes = totalWriteBytes;
            this.firstSample = firstSample;
            this.failureReason = failureReason;
        }

        static Result ok(Map<String, DiskIoRate> rates, long totalReadBytes, long totalWriteBytes) {
            return new Result(rates, totalReadBytes, totalWriteBytes, false, null);
        }

        static Result firstSample() {
            return new Result(Map.of(), 0, 0, true, null);
        }

        static Result empty(String reason) {
            return new Result(Map.of(), 0, 0, false, reason);
        }
    }
}
