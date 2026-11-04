/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 定位多 writer 实例写同一默认文件时的 tmp 竞争与 ACL 行为。 */
class AgentHistoryStoreCollisionTest {
    @TempDir Path directory;

    @Test
    void twoWritersSaveSameFileConcurrently() throws Exception {
        Path file = directory.resolve("agent_history.json");
        AgentHistoryStore store = new AgentHistoryStore(file);
        AgentHistoryWriter w1 = new AgentHistoryWriter(store);
        AgentHistoryWriter w2 = new AgentHistoryWriter(store);
        AgentHistoryStore.TaskRecord task = new AgentHistoryStore.TaskRecord(
                "root@h:22", "t-1", "任务", 1, 1L, List.of());

        CountDownLatch both = new CountDownLatch(2);
        Thread a = new Thread(() -> { w1.submit(List.of(task)); both.countDown(); });
        Thread b = new Thread(() -> { w2.submit(List.of(task)); both.countDown(); });
        a.start(); b.start();
        assertTrue(both.await(5, TimeUnit.SECONDS));
        // 两个防抖任务都触发：两个 writer 线程会同时写同一个 .tmp 文件名
        Thread.sleep(1500);
        w1.flush(); w2.flush();

        List<AgentHistoryStore.TaskRecord> loaded = store.load();
        assertEquals(1, loaded.size(), "并发写盘竞争不应让历史变空: " + loaded);
    }

    @Test
    void repeatedSavesOnSameFileKeepContent() throws Exception {
        Path file = directory.resolve("agent_history.json");
        AgentHistoryStore store = new AgentHistoryStore(file);
        for (int i = 0; i < 5; i++) {
            store.save(List.of(new AgentHistoryStore.TaskRecord(
                    "root@h:22", "t-" + i, "任务" + i, i + 1, 1L, List.of())));
        }
        List<AgentHistoryStore.TaskRecord> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("任务4", loaded.get(0).title());
        assertTrue(Files.exists(file), "最终文件必须存在");
    }
}
