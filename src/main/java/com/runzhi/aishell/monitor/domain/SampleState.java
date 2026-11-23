/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor.domain;

/**
 * 采样数据状态。
 *
 * <p>用于区分「有效零值」「首次采样」「失败」「权限不足/降级」和「已过期」：
 * 采集失败时保留最后成功值并标注已过期；没有历史数据时显示空占位。
 */
public enum SampleState {

    /** 从未采集到过数据，界面应显示占位符（—）。 */
    NO_DATA("无数据"),

    /** 首次差分采样：基线已建立，速率尚不可用，界面应显示「采样中」。 */
    FIRST_SAMPLE("采样中"),

    /** 数据有效，包括合法的零值。 */
    OK("有效"),

    /** 本次采集失败，显示的是上次保留值，界面应标注「已过期」。 */
    STALE("已过期"),

    /** 采集失败且没有可保留的旧值。 */
    FAILED("采集失败"),

    /** 远端能力不足（命令不存在、权限不足等），按项降级并说明原因。 */
    DEGRADED("已降级");

    private final String display;

    SampleState(String display) {
        this.display = display;
    }

    /** 界面展示用的状态文本。 */
    public String display() {
        return display;
    }

    /** 是否应沿用上次的保留值展示。 */
    public boolean retainsLastValue() {
        return this == STALE;
    }

    /** 是否为可用数据（含合法零值）。 */
    public boolean isUsable() {
        return this == OK || this == STALE;
    }
}
