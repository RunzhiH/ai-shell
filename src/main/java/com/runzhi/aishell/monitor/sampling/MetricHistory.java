/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.sampling;

import java.util.ArrayList;
import java.util.List;

/**
 * 有界历史缓存：曲线点数与条目数均设置上限（方案 §7「历史缓存和曲线点数设置上限」）。
 * 趋势支持最近 1、5、15 分钟，按时间窗口截取，不承诺跨重启持久化。
 */
public class MetricHistory {

    /** 默认最多保留的点数（约 2 秒采样间隔 × 15 分钟）。 */
    public static final int DEFAULT_MAX_POINTS = 600;

    private final int maxPoints;
    private final List<long[]> points = new ArrayList<>();

    public MetricHistory() {
        this(DEFAULT_MAX_POINTS);
    }

    public MetricHistory(int maxPoints) {
        this.maxPoints = Math.max(8, maxPoints);
    }

    /** 添加一个采样点（epoch 毫秒 + 数值）。 */
    public void add(long epochMs, double value) {
        points.add(new long[] { epochMs, (long) value });
        while (points.size() > maxPoints) {
            points.remove(0);
        }
    }

    public void clear() {
        points.clear();
    }

    public int size() {
        return points.size();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** 最后一个采样点的数值，没有时返回默认值。 */
    public double lastValue(double fallback) {
        if (points.isEmpty()) return fallback;
        return points.get(points.size() - 1)[1];
    }

    /** 最近一个采样点的时间。 */
    public long lastEpochMs() {
        if (points.isEmpty()) return 0L;
        return points.get(points.size() - 1)[0];
    }

    /**
     * 截取最近 rangeMs 毫秒内的点。
     *
     * @param now 参考时间（epoch 毫秒）
     */
    public List<double[]> window(long now, long rangeMs) {
        long from = now - rangeMs;
        List<double[]> result = new ArrayList<>();
        for (long[] point : points) {
            if (point[0] >= from) {
                result.add(new double[] { point[0], point[1] });
            }
        }
        return result;
    }
}
