/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.runzhi.aishell.common.logging.LoggerManager;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 历史的异步写入器：单线程 daemon 执行器 + 500ms 防抖。
 *
 * <p>调用方（FX 线程）生成不可变快照后提交，写盘在后台进行，不阻塞 UI；
 * 连续的保存请求只写最后一次。关闭时 {@link #shutdown()} 同步 flush 最多等待 2 秒。</p>
 */
public final class AgentHistoryWriter implements AutoCloseable {
    private static final Logger LOG = LoggerManager.getLogger(AgentHistoryWriter.class);
    private static final long DEBOUNCE_MILLIS = 500;
    private static final long FLUSH_TIMEOUT_MILLIS = 2000;

    private final AgentHistoryStore store;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile List<AgentHistoryStore.TaskRecord> pending;
    private volatile ScheduledFuture<?> scheduled;
    private final Object flushLock = new Object();

    public AgentHistoryWriter(AgentHistoryStore store) {
        this.store = store;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-history-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 提交一份不可变快照；防抖窗口内多次提交只写最后一次。 */
    public void submit(List<AgentHistoryStore.TaskRecord> snapshot) {
        if (snapshot == null) return;
        pending = List.copyOf(snapshot);
        dirty.set(true);
        scheduled = executor.schedule(this::writeIfDirty, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** 立即同步写一次（供测试与显式 flush 场景使用）。 */
    public void flush() {
        writeIfDirty();
    }

    private void writeIfDirty() {
        if (!dirty.get()) return;
        List<AgentHistoryStore.TaskRecord> snapshot = pending;
        if (snapshot == null) return;
        synchronized (flushLock) {
            try {
                store.save(snapshot);
            } catch (Exception failure) {
                LOG.warn("异步保存 Agent 历史失败: {}", failure.getMessage());
            } finally {
                dirty.set(false);
                flushLock.notifyAll();
            }
        }
    }

    /** 关闭：同步 flush 待写内容，最多等待 2 秒，超时也不丢弃已提交快照的最终写入机会。 */
    public void shutdown() {
        try {
            flush();
        } finally {
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
