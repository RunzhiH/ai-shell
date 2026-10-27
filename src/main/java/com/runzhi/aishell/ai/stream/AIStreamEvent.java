/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.stream;

public record AIStreamEvent(Type type, String text, Throwable error) {
    public enum Type { STARTED, DELTA, COMPLETED, CANCELLED, ERROR }

    public static AIStreamEvent of(Type type, String text) { return new AIStreamEvent(type, text, null); }
    public static AIStreamEvent error(Throwable error) {
        return new AIStreamEvent(Type.ERROR, error == null ? "Unknown AI error" : error.getMessage(), error);
    }
}
