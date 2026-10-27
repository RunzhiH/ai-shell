/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.stream;

import java.util.concurrent.Future;

public final class AIRequestHandle {
    private volatile Future<?> future;
    void attach(Future<?> future) { this.future = future; }
    public boolean cancel() {
        Future<?> current = future;
        return current != null && current.cancel(true);
    }
    public boolean isDone() {
        Future<?> current = future;
        return current != null && current.isDone();
    }
}
