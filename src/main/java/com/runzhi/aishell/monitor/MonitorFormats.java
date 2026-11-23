/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

/**
 * 监控数值的展示格式化。
 *
 * <p>内部始终保存数值与明确单位，仅在展示时格式化为 KiB/s、MiB/s 等；
 * 展示字符串不回传解析（方案 §6）。
 */
public final class MonitorFormats {

    private MonitorFormats() {
    }

    /** 速率格式化：字节/秒 → 自适应单位（B/s、KiB/s、MiB/s、GiB/s）。 */
    public static String formatBytesPerSecond(double bytesPerSec) {
        if (bytesPerSec < 0 || Double.isNaN(bytesPerSec) || Double.isInfinite(bytesPerSec)) return "—";
        if (bytesPerSec < 1024) return String.format("%.0f B/s", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format("%.1f KiB/s", bytesPerSec / 1024.0);
        if (bytesPerSec < 1024 * 1024 * 1024) return String.format("%.1f MiB/s", bytesPerSec / (1024.0 * 1024.0));
        return String.format("%.1f GiB/s", bytesPerSec / (1024.0 * 1024.0 * 1024.0));
    }

    /** 累计量格式化：字节 → 自适应单位。 */
    public static String formatBytes(double bytes) {
        if (bytes < 0 || Double.isNaN(bytes) || Double.isInfinite(bytes)) return "—";
        if (bytes < 1024) return String.format("%.0f B", bytes);
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
        return String.format("%.1f TiB", bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0));
    }

    /** KiB（free / df 的 1KiB 块口径）格式化为 KB/MB/GB。 */
    public static String formatKiB(double kib) {
        if (kib < 0 || Double.isNaN(kib)) return "—";
        if (kib < 1024) return String.format("%.0f KB", kib);
        if (kib < 1024 * 1024) return String.format("%.1f MB", kib / 1024.0);
        return String.format("%.1f GB", kib / (1024.0 * 1024.0));
    }

    /** 百分比格式化。 */
    public static String formatPercent(double percent) {
        if (Double.isNaN(percent) || Double.isInfinite(percent)) return "—";
        return String.format("%.1f%%", percent);
    }

    /**
     * 兼容旧指标的速率串格式（B / KB / MB，与历史 SystemMonitor 输出一致）。
     * 旧界面与侧栏直接展示该串，新视图使用 {@link #formatBytesPerSecond}。
     */
    public static String legacyNetworkSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B";
        if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB", bytesPerSec / 1024.0);
        if (bytesPerSec < 1024 * 1024 * 1024) return String.format("%.1f MB", bytesPerSec / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytesPerSec / (1024.0 * 1024.0 * 1024.0));
    }

    /** 双向速率合并展示：↓下行 ↑上行。 */
    public static String formatBothRates(double rxBytesPerSec, double txBytesPerSec) {
        return "↓" + formatBytesPerSecond(rxBytesPerSec) + "  ↑" + formatBytesPerSecond(txBytesPerSec);
    }
}
