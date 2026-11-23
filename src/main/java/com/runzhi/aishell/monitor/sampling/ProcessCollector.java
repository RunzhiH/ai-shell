/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.sampling;

import com.runzhi.aishell.monitor.CommandResult;
import com.runzhi.aishell.monitor.CommandRunner;
import com.runzhi.aishell.monitor.domain.ProcessInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程列表采集器（方案 §5）。
 *
 * <p>约束：
 * <ul>
 *   <li>使用固定列的 ps 输出，命令行作为最后一列整段保留，不依赖固定空白拆分；</li>
 *   <li>进程身份 = PID + 启动标识（lstart），避免 PID 复用误识别；</li>
 *   <li>CPU% 为单核满载 100% 口径（允许多线程进程超过 100%）；</li>
 *   <li>单行解析失败只跳过该进程，不使整张表失败；远端能力不足时按项降级。</li>
 * </ul>
 */
@Slf4j
public class ProcessCollector {

    /**
     * 固定 15 列 + 命令行尾列，使用 split 限量保留命令行中的空格与特殊字符：
     * pid ppid user pcpu pmem rss stat nlwp [lstart×5] etime comm args
     */
    public static final String PS_COMMAND =
            "ps -eo pid,ppid,user,pcpu,pmem,rss,stat,nlwp,lstart,etime,comm,args --no-headers";

    /** 固定前缀列数：8 个数值/文本列 + 5 个启动时间列 + 运行时长 + 进程名。 */
    private static final int FIXED_COLUMNS = 15;
    private static final int ARGS_INDEX = FIXED_COLUMNS;

    /** 身份缓存上限，避免异常输出撑爆内存。 */
    private static final int MAX_IDENTITY_CACHE = 8192;

    private static final int PS_TIMEOUT_MS = 15000;

    private final CommandRunner runner;
    private final Map<String, ProcessInfo> prevByIdentity = new LinkedHashMap<>();

    public ProcessCollector(CommandRunner runner) {
        this.runner = runner;
    }

    public void resetBaseline() {
        prevByIdentity.clear();
    }

    /** 采集进程列表；失败时返回带原因的降级结果。 */
    public Result collect() {
        CommandResult result = runner.run(PS_COMMAND, PS_TIMEOUT_MS);
        if (!result.hasOutput()) {
            return Result.empty(result.isTimedOut() ? "进程列表采样超时" : "无法执行 ps 命令");
        }
        List<ProcessInfo> processes = new ArrayList<>();
        int skipped = 0;
        for (String rawLine : result.getOutput().split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            ProcessInfo info = parseLine(line);
            if (info != null) {
                processes.add(info);
            } else {
                skipped++;
            }
        }
        if (processes.isEmpty() && skipped > 0) {
            return Result.degraded("所有进程行解析失败（ps 输出格式不兼容）");
        }
        applyResourceDeltas(processes);
        return Result.ok(processes, skipped);
    }

    /** 解析单行：列数不足时按缺失字段降级，不整表失败。 */
    static ProcessInfo parseLine(String line) {
        String[] fields = line.split("\\s+", ARGS_INDEX + 1);
        if (fields.length < ARGS_INDEX) return null;
        try {
            int pid = parsePositiveInt(fields[0]);
            int ppid = parsePositiveInt(fields[1]);
            if (pid < 0) return null;
            String user = fields[2];

            double cpuPercent = parseDoubleOr(fields[3], 0.0);
            double memPercent = parseDoubleOr(fields[4], 0.0);
            long rssKiB = parseLongOr(fields[5], 0L);
            String state = fields[6];
            int threadCount = fields.length > 7 ? parsePositiveInt(fields[7]) : -1;

            // lstart 占 5 列：weekday month day time year（内部空白归一化，保证身份键稳定）
            String startedAt = "";
            if (fields.length > 12) {
                startedAt = (fields[8] + " " + fields[9] + " " + fields[10] + " "
                        + fields[11] + " " + fields[12]).replaceAll("\\s+", " ").trim();
            }
            String elapsed = fields.length > 13 ? fields[13] : "";
            // comm 列为内核态进程名（已去括号），命令行作为最后一列整段保留
            String comm = fields.length > 14 ? fields[14] : "";
            String commandLine = fields.length > ARGS_INDEX ? fields[ARGS_INDEX] : "";
            if (commandLine.isEmpty()) return null;

            String name = !comm.isEmpty() ? comm : deriveName(commandLine);
            String identityKey = startedAt.isEmpty() ? Integer.toString(pid) : pid + "@" + startedAt;

            ProcessInfo.ProcessInfoBuilder builder = ProcessInfo.builder()
                    .pid(pid)
                    .ppid(ppid >= 0 ? ppid : -1)
                    .user(user)
                    .cpuPercent(cpuPercent)
                    .memPercent(memPercent)
                    .rssKiB(rssKiB)
                    .state(state)
                    .threadCount(threadCount)
                    .startedAt(startedAt)
                    .elapsed(elapsed)
                    .name(name)
                    .commandLine(commandLine)
                    .identityKey(identityKey);
            // 权限不足时 user/部分字段可能为 "-"：仍保留该行
            return builder.build();
        } catch (Exception e) {
            log.debug("Skipping unparseable ps line: {}", e.getMessage());
            return null;
        }
    }

    /** comm 缺失时以命令行首个分量兜底（去括号、取 basename）。 */
    private static String deriveName(String commandLine) {
        String first = commandLine.split("\\s+", 2)[0];
        // 内核线程的 args 列显示为 [kworker/0:0] 之类
        if (first.startsWith("[") && first.endsWith("]") && first.length() > 2) {
            first = first.substring(1, first.length() - 1);
        }
        int slash = first.lastIndexOf('/');
        String base = slash >= 0 ? first.substring(slash + 1) : first;
        return base.isEmpty() ? "?" : base;
    }

    private static int parsePositiveInt(String field) {
        try {
            return Integer.parseInt(field.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parseDoubleOr(String field, double fallback) {
        try {
            return Double.parseDouble(field.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongOr(String field, long fallback) {
        try {
            return Long.parseLong(field.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 计算与上次采样之间的 RSS / CPU 变化（详情区「资源变化」）。 */
    private void applyResourceDeltas(List<ProcessInfo> processes) {
        Map<String, ProcessInfo> next = new LinkedHashMap<>();
        for (ProcessInfo info : processes) {
            ProcessInfo prev = prevByIdentity.get(info.getIdentityKey());
            ProcessInfo updated = prev == null ? info : info.toBuilder()
                    .rssDeltaKiB(info.getRssKiB() - prev.getRssKiB())
                    .cpuDelta(info.getCpuPercent() - prev.getCpuPercent())
                    .build();
            next.put(updated.getIdentityKey(), updated);
        }
        if (next.size() > MAX_IDENTITY_CACHE) {
            // 超出上限时只保留最新部分，避免异常远端撑爆缓存
            List<String> keys = new ArrayList<>(next.keySet());
            for (String key : keys.subList(0, keys.size() - MAX_IDENTITY_CACHE)) {
                next.remove(key);
            }
        }
        prevByIdentity.clear();
        prevByIdentity.putAll(next);
        // 替换列表引用为带增量信息的实例
        processes.clear();
        processes.addAll(next.values());
    }

    /** 状态首字母计数：运行/睡眠/停止/僵尸，供旧指标的进程状态分布使用。 */
    public static int[] stateCounts(List<ProcessInfo> processes) {
        int running = 0, sleeping = 0, stopped = 0, zombie = 0;
        for (ProcessInfo info : processes) {
            String state = info.getState();
            char first = state == null || state.isEmpty() ? '?' : state.charAt(0);
            switch (first) {
                case 'R' -> running++;
                case 'S', 'D', 'I' -> sleeping++;
                case 'T' -> stopped++;
                case 'Z' -> zombie++;
                default -> {
                    // 未知状态计入睡眠，保持总数一致
                    sleeping++;
                }
            }
        }
        return new int[] { running, sleeping, stopped, zombie };
    }

    public static class Result {
        public final List<ProcessInfo> processes;
        public final boolean degraded;
        public final int skippedRows;
        public final String failureReason;

        private Result(List<ProcessInfo> processes, boolean degraded, int skippedRows, String failureReason) {
            this.processes = processes;
            this.degraded = degraded;
            this.skippedRows = skippedRows;
            this.failureReason = failureReason;
        }

        static Result ok(List<ProcessInfo> processes, int skippedRows) {
            return new Result(processes, false, skippedRows, null);
        }

        static Result degraded(String reason) {
            return new Result(List.of(), true, 0, reason);
        }

        static Result empty(String reason) {
            return new Result(List.of(), false, 0, reason);
        }

        public boolean failed() {
            return failureReason != null && !degraded && processes.isEmpty();
        }
    }
}
