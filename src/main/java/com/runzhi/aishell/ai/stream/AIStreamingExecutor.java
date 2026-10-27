/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ai.stream;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.StreamingAIAdapter;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Unified request lifecycle. Adapters may later replace the single DELTA with provider-native chunks. */
public final class AIStreamingExecutor {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-stream-request");
        thread.setDaemon(true);
        return thread;
    });

    public AIRequestHandle submit(AIAdapter adapter, String prompt, Consumer<AIStreamEvent> listener) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(listener, "listener");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        AIRequestHandle handle = new AIRequestHandle();
        handle.attach(EXECUTOR.submit(() -> {
            listener.accept(AIStreamEvent.of(AIStreamEvent.Type.STARTED, ""));
            try {
                if (adapter instanceof StreamingAIAdapter streaming) {
                    streaming.streamQuery(prompt,
                            delta -> {
                                if (!Thread.currentThread().isInterrupted() && delta != null && !delta.isEmpty()) {
                                    listener.accept(AIStreamEvent.of(AIStreamEvent.Type.DELTA, delta));
                                }
                            }, () -> Thread.currentThread().isInterrupted());
                } else {
                    String response = adapter.sendQuery(prompt);
                    if (!Thread.currentThread().isInterrupted()) {
                        // 非流式适配器以 "Error: ..." 字符串表达失败：直接转成 ERROR 事件，
                        // 否则调用方看到"成功完成"但内容是错误文本（2026-10-23 审计 P1-8）
                        if (response != null && response.startsWith("Error:")) {
                            listener.accept(AIStreamEvent.error(new java.io.IOException(response)));
                            return;
                        }
                        listener.accept(AIStreamEvent.of(AIStreamEvent.Type.DELTA, response == null ? "" : response));
                    }
                }
                if (Thread.currentThread().isInterrupted()) {
                    listener.accept(AIStreamEvent.of(AIStreamEvent.Type.CANCELLED, ""));
                    return;
                }
                listener.accept(AIStreamEvent.of(AIStreamEvent.Type.COMPLETED, ""));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    listener.accept(AIStreamEvent.of(AIStreamEvent.Type.CANCELLED, ""));
                } else {
                    listener.accept(AIStreamEvent.error(e));
                }
            }
        }));
        return handle;
    }
}
