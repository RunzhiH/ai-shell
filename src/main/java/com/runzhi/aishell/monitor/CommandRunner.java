/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

import com.runzhi.aishell.common.util.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级远程命令执行器。
 *
 * <p>约束：
 * <ul>
 *   <li>每个会话限制并发命令数，避免慢连接上排挤其他会话；</li>
 *   <li>命令超时与失败分别返回状态，由调用方按指标降级；</li>
 *   <li>会话停止后不再发起新命令，且把预期失败降级为 DEBUG 日志。</li>
 * </ul>
 */
@Slf4j
public class CommandRunner {

    /** 每个监控会话最多同时执行的命令数。 */
    private static final int MAX_CONCURRENT_COMMANDS = 3;

    private final DataSourceProvider dataSourceProvider;
    private final Semaphore concurrencyLimit = new Semaphore(MAX_CONCURRENT_COMMANDS, true);
    private final AtomicBoolean stopped;

    /** 命令编号，仅用于日志定位，不输出命令原文。 */
    private volatile int commandSeq;

    public CommandRunner(DataSourceProvider dataSourceProvider, AtomicBoolean stopped) {
        this.dataSourceProvider = dataSourceProvider;
        this.stopped = stopped;
    }

    /**
     * 执行命令并在指定超时后放弃等待。
     *
     * @param command  预定义的只读采集命令
     * @param timeoutMs 超时（毫秒）
     */
    public CommandResult run(String command, int timeoutMs) {
        if (stopped.get() || dataSourceProvider == null) {
            return CommandResult.failure();
        }
        CompletableFuture<CommandResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (!concurrencyLimit.tryAcquire(5, TimeUnit.SECONDS)) {
                    log.debug("Monitor session busy, command skipped #{}", commandSeq);
                    return CommandResult.failure();
                }
                try {
                    int seq = ++commandSeq;
                    if (!dataSourceProvider.isConnected()) {
                        return CommandResult.failure();
                    }
                    String output = dataSourceProvider.executeCommand(command);
                    return CommandResult.success(output);
                } finally {
                    concurrencyLimit.release();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CommandResult.failure();
            } catch (Exception e) {
                if (stopped.get() || !isConnected()) {
                    log.debug("Monitor command stopped with session: {}", e.getMessage());
                } else {
                    // 命令本身可能包含路径，但不包含凭据；失败原因用于降级判断
                    log.warn("Monitor command failed: {}", e.getMessage());
                }
                return CommandResult.failure();
            }
        }, ThreadPoolManager.getInstance().getIoThreadPool());

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.debug("Monitor command timed out after {}ms", timeoutMs);
            return CommandResult.timeout();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return CommandResult.failure();
        } catch (Exception e) {
            future.cancel(true);
            return CommandResult.failure();
        }
    }

    private boolean isConnected() {
        try {
            return dataSourceProvider != null && dataSourceProvider.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }
}
