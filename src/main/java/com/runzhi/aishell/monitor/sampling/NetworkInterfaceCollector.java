/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.sampling;

import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.monitor.CommandResult;
import com.runzhi.aishell.monitor.CommandRunner;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceInfo;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceRate;
import com.runzhi.aishell.monitor.domain.NetworkInterfaceStats;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 多接口网络采集器：每接口分别计算差分，接口新增、删除、重连或计数器重置时
 * 只重建该接口的基线，不影响其他接口（方案 §6）。
 *
 * <p>速率保存为字节/秒等数值，由界面格式化为 KiB/s、MiB/s；不从展示字符串反向解析。
 */
@Slf4j
public class NetworkInterfaceCollector {

    public static final String NET_DEV_COMMAND = "cat /proc/net/dev";
    public static final String ADDR_COMMAND = "ip -o addr show 2>/dev/null";

    private final CommandRunner runner;
    private final LongSupplier nanoClock;

    private final Map<String, NetworkInterfaceStats> prevStats = new LinkedHashMap<>();
    private boolean firstSample = true;

    public NetworkInterfaceCollector(CommandRunner runner) {
        this(runner, System::nanoTime);
    }

    public NetworkInterfaceCollector(CommandRunner runner, LongSupplier nanoClock) {
        this.runner = runner;
        this.nanoClock = nanoClock;
    }

    public void resetBaseline() {
        prevStats.clear();
        firstSample = true;
    }

    /** 判定接口是否属于（可能与其他接口流量重叠的）虚拟/桥接接口。 */
    public static boolean isLikelyVirtual(String name) {
        if (name == null || name.isEmpty() || "lo".equals(name)) return true;
        return name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-")
                || name.startsWith("virbr") || name.startsWith("tap") || name.startsWith("tun")
                || name.startsWith("vnet") || name.startsWith("wg") || name.startsWith("utun");
    }

    /**
     * 采集一轮接口统计并计算每接口速率。
     *
     * @return 速率采集结果（含旧指标需要的合计值）
     */
    public Result collect() {
        CommandResult result = runner.run(NET_DEV_COMMAND, AppConstants.NET_COMMAND_TIMEOUT);
        if (!result.hasOutput()) {
            return Result.empty(result.isTimedOut() ? "网络接口采样超时" : "无法读取 /proc/net/dev");
        }

        long capturedNanos = nanoClock.getAsLong();
        Map<String, NetworkInterfaceStats> current = parseNetDev(result.getOutput(), capturedNanos);

        Map<String, NetworkInterfaceRate> rates = new LinkedHashMap<>();
        long aggregateRx = 0L;
        long aggregateTx = 0L;
        long aggregateRxRate = 0L;
        long aggregateTxRate = 0L;

        if (firstSample) {
            prevStats.putAll(current);
            firstSample = false;
            for (String name : current.keySet()) {
                if ("lo".equals(name)) continue;
                aggregateRx += current.get(name).getRxBytes();
                aggregateTx += current.get(name).getTxBytes();
            }
            return Result.firstSample(aggregateRx, aggregateTx);
        }

        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, NetworkInterfaceStats> entry : current.entrySet()) {
            String name = entry.getKey();
            NetworkInterfaceStats cur = entry.getValue();
            NetworkInterfaceStats prev = prevStats.get(name);

            NetworkInterfaceRate rate;
            if (prev == null) {
                rate = NetworkInterfaceRate.builder()
                        .name(name)
                        .totalRxBytes(cur.getRxBytes())
                        .totalTxBytes(cur.getTxBytes())
                        .state(com.runzhi.aishell.monitor.domain.SampleState.FIRST_SAMPLE)
                        .reason("接口首次出现，建立基线")
                        .build();
            } else if (cur.getRxBytes() < prev.getRxBytes() || cur.getTxBytes() < prev.getTxBytes()) {
                // 计数器下降（重置或重连）：重建该接口基线
                rate = NetworkInterfaceRate.builder()
                        .name(name)
                        .totalRxBytes(cur.getRxBytes())
                        .totalTxBytes(cur.getTxBytes())
                        .state(com.runzhi.aishell.monitor.domain.SampleState.FIRST_SAMPLE)
                        .reason("计数器重置，重建基线")
                        .build();
            } else {
                double seconds = Math.max(0.001, (cur.getCapturedNanos() - prev.getCapturedNanos()) / 1_000_000_000.0);
                rate = NetworkInterfaceRate.builder()
                        .name(name)
                        .rxBytesPerSec((cur.getRxBytes() - prev.getRxBytes()) / seconds)
                        .txBytesPerSec((cur.getTxBytes() - prev.getTxBytes()) / seconds)
                        .rxPacketsPerSec((cur.getRxPackets() - prev.getRxPackets()) / seconds)
                        .txPacketsPerSec((cur.getTxPackets() - prev.getTxPackets()) / seconds)
                        .rxErrorDelta(cur.getRxErrors() - prev.getRxErrors())
                        .rxDropDelta(cur.getRxDrops() - prev.getRxDrops())
                        .txErrorDelta(cur.getTxErrors() - prev.getTxErrors())
                        .txDropDelta(cur.getTxDrops() - prev.getTxDrops())
                        .totalRxBytes(cur.getRxBytes())
                        .totalTxBytes(cur.getTxBytes())
                        .state(com.runzhi.aishell.monitor.domain.SampleState.OK)
                        .build();
            }
            rates.put(name, rate);

            // 合计只统计非 lo 接口，供旧指标字段使用
            if (!"lo".equals(name)) {
                aggregateRx += cur.getRxBytes();
                aggregateTx += cur.getTxBytes();
                if (rate.getState() == com.runzhi.aishell.monitor.domain.SampleState.OK) {
                    aggregateRxRate += rate.getRxBytesPerSec();
                    aggregateTxRate += rate.getTxBytesPerSec();
                }
            }
        }

        for (String name : prevStats.keySet()) {
            if (!current.containsKey(name)) gone.add(name);
        }
        for (String device : gone) prevStats.remove(device);
        prevStats.keySet().retainAll(current.keySet());
        prevStats.putAll(current);

        return Result.ok(rates, aggregateRx, aggregateTx, aggregateRxRate, aggregateTxRate);
    }

    /** 低频接口信息刷新（IP、MTU、状态、链路速率）。设备名来自 /proc/net/dev，不含空白。 */
    public Map<String, NetworkInterfaceInfo> refreshInterfaceInfo(java.util.Collection<String> interfaces) {
        Map<String, NetworkInterfaceInfo.NetworkInterfaceInfoBuilder> builders = new LinkedHashMap<>();
        for (String name : interfaces) {
            builders.put(name, NetworkInterfaceInfo.builder().name(name));
        }
        if (builders.isEmpty()) return Map.of();

        StringBuilder sysfsCommand = new StringBuilder("for d in");
        for (String name : interfaces) sysfsCommand.append(' ').append(name);
        sysfsCommand.append("; do printf '%s|%s|%s|%s\n' $d $(cat /sys/class/net/$d/operstate 2>/dev/null) "
                + "$(cat /sys/class/net/$d/mtu 2>/dev/null) $(cat /sys/class/net/$d/speed 2>/dev/null); done");
        CommandResult sysfsResult = runner.run(sysfsCommand.toString(), AppConstants.NET_COMMAND_TIMEOUT);
        if (sysfsResult.hasOutput()) {
            for (String line : sysfsResult.getOutput().split("\\R")) {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 1) continue;
                NetworkInterfaceInfo.NetworkInterfaceInfoBuilder builder = builders.get(parts[0].trim());
                if (builder == null) continue;
                if (parts.length > 1 && !parts[1].trim().isEmpty()) builder.operState(parts[1].trim());
                if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                    try {
                        builder.mtu(Integer.parseInt(parts[2].trim()));
                    } catch (NumberFormatException ignored) {
                        // MTU 不可获得时保持未知
                    }
                }
                if (parts.length > 3 && !parts[3].trim().isEmpty()) {
                    try {
                        builder.speedMbps(Integer.parseInt(parts[3].trim()));
                    } catch (NumberFormatException ignored) {
                        // 链路速率无法获取时保持未知，不计算带宽利用率
                    }
                }
            }
        }

        // 地址信息需要 ip 命令；不存在时按项降级（界面显示未知）
        CommandResult addrResult = runner.run(ADDR_COMMAND, AppConstants.NET_COMMAND_TIMEOUT);
        Map<String, String> ipv4ByName = new HashMap<>();
        Map<String, String> ipv6ByName = new HashMap<>();
        if (addrResult.hasOutput()) {
            for (String line : addrResult.getOutput().split("\\R")) {
                // ip -o 行形如 "2: eth0: <...>"：接口名位于第一个与第二个冒号之间
                int first = line.indexOf(':');
                int second = first >= 0 ? line.indexOf(':', first + 1) : -1;
                String name;
                if (second > first) {
                    name = line.substring(first + 1, second).trim();
                } else if (first > 0) {
                    name = line.substring(0, first).trim();
                } else {
                    continue;
                }
                if (!builders.containsKey(name)) continue;
                java.util.regex.Matcher inet = java.util.regex.Pattern
                        .compile("\\binet (\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(line);
                if (inet.find()) {
                    ipv4ByName.putIfAbsent(name, inet.group(1));
                    continue;
                }
                java.util.regex.Matcher inet6 = java.util.regex.Pattern
                        .compile("\\binet6 ([0-9a-fA-F:]+)").matcher(line);
                if (inet6.find()) {
                    ipv6ByName.putIfAbsent(name, inet6.group(1));
                }
            }
        }
        for (Map.Entry<String, String> entry : ipv4ByName.entrySet()) {
            builders.get(entry.getKey()).ipv4Address(entry.getValue());
        }
        for (Map.Entry<String, String> entry : ipv6ByName.entrySet()) {
            builders.get(entry.getKey()).ipv6Address(entry.getValue());
        }

        Map<String, NetworkInterfaceInfo> info = new LinkedHashMap<>();
        for (Map.Entry<String, NetworkInterfaceInfo.NetworkInterfaceInfoBuilder> entry : builders.entrySet()) {
            info.put(entry.getKey(), entry.getValue().build());
        }
        return info;
    }

    /** 解析 /proc/net/dev：两行表头跳过，接口行按冒号定位，列数不足跳过。 */
    static Map<String, NetworkInterfaceStats> parseNetDev(String output, long capturedNanos) {
        Map<String, NetworkInterfaceStats> stats = new LinkedHashMap<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String ifname = line.substring(0, colon).trim();
            if (ifname.isEmpty() || ifname.indexOf('|') >= 0
                    || ifname.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            String[] parts = line.substring(colon + 1).trim().split("\\s+");
            if (parts.length < 16) continue;
            try {
                stats.put(ifname, NetworkInterfaceStats.builder()
                        .name(ifname)
                        .rxBytes(Long.parseLong(parts[0]))
                        .rxPackets(Long.parseLong(parts[1]))
                        .rxErrors(Long.parseLong(parts[2]))
                        .rxDrops(Long.parseLong(parts[3]))
                        .txBytes(Long.parseLong(parts[8]))
                        .txPackets(Long.parseLong(parts[9]))
                        .txErrors(Long.parseLong(parts[10]))
                        .txDrops(Long.parseLong(parts[11]))
                        .capturedNanos(capturedNanos)
                        .build());
            } catch (NumberFormatException e) {
                log.debug("Error parsing network stats for {}: {}", ifname, e.getMessage());
            }
        }
        return stats;
    }

    /** 采集结果：每接口速率 + 旧指标字段需要的合计值。 */
    public static class Result {
        public final Map<String, NetworkInterfaceRate> rates;
        public final long totalRxBytes;
        public final long totalTxBytes;
        /** 合计上传/下载速率（非 lo）。首次采样后为 -1 表示尚不可用。 */
        public final long aggregateRxBytesPerSec;
        public final long aggregateTxBytesPerSec;
        public final boolean firstSample;
        public final String failureReason;

        private Result(Map<String, NetworkInterfaceRate> rates, long totalRxBytes, long totalTxBytes,
                       long aggregateRxBytesPerSec, long aggregateTxBytesPerSec,
                       boolean firstSample, String failureReason) {
            this.rates = rates;
            this.totalRxBytes = totalRxBytes;
            this.totalTxBytes = totalTxBytes;
            this.aggregateRxBytesPerSec = aggregateRxBytesPerSec;
            this.aggregateTxBytesPerSec = aggregateTxBytesPerSec;
            this.firstSample = firstSample;
            this.failureReason = failureReason;
        }

        static Result ok(Map<String, NetworkInterfaceRate> rates, long totalRxBytes, long totalTxBytes,
                         long aggregateRxBytesPerSec, long aggregateTxBytesPerSec) {
            return new Result(rates, totalRxBytes, totalTxBytes, aggregateRxBytesPerSec, aggregateTxBytesPerSec,
                    false, null);
        }

        static Result firstSample(long totalRxBytes, long totalTxBytes) {
            return new Result(Map.of(), totalRxBytes, totalTxBytes, -1, -1, true, null);
        }

        static Result empty(String reason) {
            return new Result(Map.of(), 0, 0, -1, -1, false, reason);
        }
    }

}
