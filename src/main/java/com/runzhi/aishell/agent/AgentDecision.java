/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import java.util.List;

/** One bounded step produced by the Agent planner. */
public record AgentDecision(Type type, String content, String context, List<String> options) {
    public enum Type { COMMAND, COMPLETE, ASK }

    public AgentDecision(Type type, String content, String context) {
        this(type, content, context, List.of());
    }
}
