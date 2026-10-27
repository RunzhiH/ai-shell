/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

public enum CommandRisk {
    LOW("低风险", "#3fb950"),
    MEDIUM("中风险", "#d29922"),
    HIGH("高风险", "#f85149");

    private final String label;
    private final String color;

    CommandRisk(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() { return label; }
    public String color() { return color; }
}
