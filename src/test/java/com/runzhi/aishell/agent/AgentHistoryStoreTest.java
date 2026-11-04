/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Agent 会话历史必须能跨进程保存和恢复可回放消息。 */
class AgentHistoryStoreTest {
    @TempDir Path directory;

    @Test
    void savesAndLoadsTasksWithReplayableEvents() {
        Path file = directory.resolve("agent_history.json");
        AgentHistoryStore store = new AgentHistoryStore(file);
        AgentHistoryStore.EventRecord ask = new AgentHistoryStore.EventRecord(
                "session-1", "ASK", "请选择", 1000L, List.of("A", "B"), null);
        AgentHistoryStore.EventRecord answer = new AgentHistoryStore.EventRecord(
                "session-1", "USER", "2", 2000L, List.of(), null);

        store.save(List.of(new AgentHistoryStore.TaskRecord(
                "root@example:22", "task-1", "部署任务", 1, 3000L, List.of(ask, answer))));

        List<AgentHistoryStore.TaskRecord> loaded = store.load();
        assertEquals(1, loaded.size());
        AgentHistoryStore.TaskRecord task = loaded.get(0);
        assertEquals("root@example:22", task.connectionKey());
        assertEquals("task-1", task.id());
        assertEquals("部署任务", task.title());
        assertEquals(2, task.events().size());

        AgentActivityBus.Event restoredAsk = AgentHistoryStore.toEvent(task.events().get(0));
        assertNotNull(restoredAsk);
        assertEquals(AgentActivityBus.Kind.ASK, restoredAsk.kind());
        assertEquals(List.of("A", "B"), restoredAsk.options());
        assertNull(restoredAsk.answerHandler(), "历史问题不再可回答");

        AgentActivityBus.Event restoredAnswer = AgentHistoryStore.toEvent(task.events().get(1));
        assertNotNull(restoredAnswer);
        assertEquals(AgentActivityBus.Kind.USER, restoredAnswer.kind());
        assertEquals("2", restoredAnswer.text());
    }

    @Test
    void missingOrBrokenFileReturnsEmptyInsteadOfThrowing() throws Exception {
        AgentHistoryStore missing = new AgentHistoryStore(directory.resolve("missing.json"));
        assertTrue(missing.load().isEmpty());

        Path broken = directory.resolve("broken.json");
        Files.writeString(broken, "{not valid json");
        AgentHistoryStore store = new AgentHistoryStore(broken);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void trimsTasksPerConnectionAndEventsPerTask() {
        AgentHistoryStore store = new AgentHistoryStore(directory.resolve("agent_history.json"));
        List<AgentHistoryStore.TaskRecord> tasks = new ArrayList<>();
        for (int taskIndex = 0; taskIndex <= AgentHistoryStore.MAX_TASKS_PER_CONNECTION; taskIndex++) {
            List<AgentHistoryStore.EventRecord> events = new ArrayList<>();
            for (int eventIndex = 0; eventIndex <= AgentHistoryStore.MAX_EVENTS_PER_TASK; eventIndex++) {
                events.add(new AgentHistoryStore.EventRecord("session", "STATUS", 
                        "event-" + eventIndex, 1000L + eventIndex, List.of(), null));
            }
            tasks.add(new AgentHistoryStore.TaskRecord("root@example:22", "task-" + taskIndex,
                    "任务", taskIndex + 1, 1000L, events));
        }

        List<AgentHistoryStore.TaskRecord> trimmed = store.trim(tasks);

        assertEquals(AgentHistoryStore.MAX_TASKS_PER_CONNECTION, trimmed.size(),
                "每连接最多保留 " + AgentHistoryStore.MAX_TASKS_PER_CONNECTION + " 个任务");
        assertEquals(AgentHistoryStore.MAX_EVENTS_PER_TASK, trimmed.get(0).events().size(),
                "每任务最多保留 " + AgentHistoryStore.MAX_EVENTS_PER_TASK + " 条消息");
    }

    @Test
    void dropsOldestTasksWhenGlobalFileSizeIsExceeded() {
        AgentHistoryStore store = new AgentHistoryStore(directory.resolve("agent_history.json"));
        List<AgentHistoryStore.TaskRecord> tasks = new ArrayList<>();
        // 每个任务约 1MB，5 个任务超过 5MB 上限；updatedAt 最旧的应被优先删除
        String big = "x".repeat(900_000);
        for (int taskIndex = 0; taskIndex < 6; taskIndex++) {
            tasks.add(new AgentHistoryStore.TaskRecord("root@example:22", "task-" + taskIndex,
                    "任务", 1, 1000L + taskIndex, List.of(new AgentHistoryStore.EventRecord(
                    "session", "RESPONSE", big, 1000L + taskIndex, List.of(), null))));
        }

        List<AgentHistoryStore.TaskRecord> trimmed = store.trim(tasks);

        assertTrue(trimmed.size() < tasks.size(), "超过全局上限必须按 updatedAt 删除最旧任务");
        assertFalse(trimmed.stream().anyMatch(task -> task.id().equals("task-0")),
                "updatedAt 最旧的任务应被优先删除");
    }

    @Test
    void writerSavesSnapshotAsynchronouslyAndFlushesOnShutdown() throws Exception {
        Path file = directory.resolve("agent_history.json");
        AgentHistoryStore store = new AgentHistoryStore(file);
        AgentHistoryWriter writer = new AgentHistoryWriter(store);

        writer.submit(List.of(new AgentHistoryStore.TaskRecord(
                "root@example:22", "task-1", "部署任务", 1, 3000L, List.of())));
        // 防抖窗口内再提交一次，最终只写最后一次快照
        writer.submit(List.of(new AgentHistoryStore.TaskRecord(
                "root@example:22", "task-2", "清理任务", 2, 4000L, List.of())));
        writer.shutdown();

        List<AgentHistoryStore.TaskRecord> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("task-2", loaded.get(0).id());
        assertEquals("清理任务", loaded.get(0).title());
    }
}
