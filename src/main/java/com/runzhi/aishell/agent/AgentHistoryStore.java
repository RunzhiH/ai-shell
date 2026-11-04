/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.common.logging.LoggerManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Agent 任务与对话历史的本地持久化。
 *
 * <p>只保存可回放的消息文本与元数据，不保存审批回调、待回答问题或任何凭据。
 * 每条连接下按任务保存，重启后可通过“历史”重新打开。</p>
 *
 * <p>容量策略（保存前裁剪，避免只靠 UI 上限）：
 * <ul>
 * <li>每连接最多 {@value #MAX_TASKS_PER_CONNECTION} 个任务；</li>
 * <li>每任务最多 {@value #MAX_EVENTS_PER_TASK} 条消息；</li>
 * <li>全文件序列化估算超过 {@value #MAX_FILE_BYTES} 字节时，按 updatedAt 删除最旧任务。</li>
 * </ul></p>
 */
public final class AgentHistoryStore {
    public record EventRecord(String sessionId, String kind, String text, long timestamp,
                              List<String> options, String proposalId) { }

    public record TaskRecord(String connectionKey, String id, String title, int ordinal,
                             long updatedAt, List<EventRecord> events) { }

    private static final Logger LOG = LoggerManager.getLogger(AgentHistoryStore.class);
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Path DEFAULT_FILE = Path.of(AppConstants.DEFAULT_CONFIG_PATH)
            .getParent().resolve("agent_history.json");
    /** 全局历史文件上限（字节）；超过后按 updatedAt 删除最旧任务。 */
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    /** 每连接保存的任务上限。 */
    static final int MAX_TASKS_PER_CONNECTION = 50;
    /** 每任务保存的消息上限。 */
    static final int MAX_EVENTS_PER_TASK = 300;

    private final Path file;

    public AgentHistoryStore() {
        this(DEFAULT_FILE);
    }

    AgentHistoryStore(Path file) {
        this.file = file;
    }

    public List<TaskRecord> load() {
        if (file == null || !Files.exists(file)) return List.of();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<TaskRecord> loaded = GSON.fromJson(reader, new TypeToken<List<TaskRecord>>() { }.getType());
            if (loaded == null) return List.of();
            List<TaskRecord> valid = new ArrayList<>();
            for (TaskRecord task : loaded) {
                if (task == null || task.connectionKey() == null || task.id() == null) continue;
                valid.add(new TaskRecord(task.connectionKey(), task.id(),
                        task.title(), Math.max(0, task.ordinal()), task.updatedAt(),
                        task.events() == null ? List.of() : List.copyOf(task.events())));
            }
            return valid;
        } catch (Exception failure) {
            LOG.warn("读取 Agent 会话历史失败: {}", failure.getMessage());
            return List.of();
        }
    }

    public void save(List<TaskRecord> tasks) {
        if (file == null) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<TaskRecord> trimmed = trim(tasks);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + Long.toHexString(System.nanoTime()));
            Files.writeString(temporary, GSON.toJson(trimmed), StandardCharsets.UTF_8);
            // Windows 下 REPLACE_EXISTING 的 rename 偶发被其它句柄短暂锁定；小重试吸收这种瞬时竞争。
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    lastFailure = null;
                    break;
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                    lastFailure = null;
                    break;
                } catch (Exception moveFailure) {
                    lastFailure = moveFailure;
                    if (attempt < 3) Thread.sleep(50L * attempt);
                }
            }
            if (lastFailure != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
                throw lastFailure;
            }
            // 只在最终文件上收紧权限；临时文件的 ACL 变更会导致同进程的 move 被拒绝。
            restrictAccess(file);
        } catch (Exception failure) {
            LOG.warn("保存 Agent 会话历史失败: {}", failure.getMessage());
        }
    }

    /**
     * 保存前裁剪：每连接任务上限、每任务消息上限、全局文件字节上限。
     * 全局上限按序列化文本字节估算，逐个删除 updatedAt 最旧的任务直到满足。
     */
    List<TaskRecord> trim(List<TaskRecord> tasks) {
        List<TaskRecord> source = tasks == null ? List.of() : tasks;
        List<TaskRecord> bounded = new ArrayList<>();
        for (TaskRecord task : source) {
            if (task == null || task.connectionKey() == null || task.id() == null) continue;
            List<EventRecord> events = task.events() == null ? List.of() : task.events();
            if (events.size() > MAX_EVENTS_PER_TASK) {
                events = new ArrayList<>(events.subList(events.size() - MAX_EVENTS_PER_TASK, events.size()));
            }
            bounded.add(new TaskRecord(task.connectionKey(), task.id(), task.title(),
                    Math.max(0, task.ordinal()), task.updatedAt(), List.copyOf(events)));
        }
        // 每连接只保留最新的 MAX_TASKS_PER_CONNECTION 个任务（按 ordinal 降序取前 N）
        List<TaskRecord> perConnectionBounded = new ArrayList<>();
        for (String connection : bounded.stream().map(TaskRecord::connectionKey).distinct().toList()) {
            List<TaskRecord> group = bounded.stream()
                    .filter(task -> task.connectionKey().equals(connection))
                    .sorted(Comparator.comparingInt(TaskRecord::ordinal).reversed())
                    .limit(MAX_TASKS_PER_CONNECTION)
                    .toList();
            perConnectionBounded.addAll(group);
        }
        // 全局字节上限：删除 updatedAt 最旧的任务，直到估算字节数满足或只剩一个任务
        List<TaskRecord> result = new ArrayList<>(perConnectionBounded);
        while (estimatedBytes(result) > MAX_FILE_BYTES && result.size() > 1) {
            result.stream()
                    .min(Comparator.comparingLong(TaskRecord::updatedAt))
                    .ifPresent(result::remove);
        }
        return List.copyOf(result);
    }

    /** 粗略估算序列化后字节数；JSON 美化会多一些空白，按 1.2 倍冗余取整。 */
    private int estimatedBytes(List<TaskRecord> tasks) {
        int chars = 4; // "[]" 与少量结构
        for (TaskRecord task : tasks) {
            chars += 96; // 记录字段与括号
            chars += length(task.connectionKey()) + length(task.id()) + length(task.title())
                    + length(task.events() == null ? "" : task.events().toString());
            if (task.events() != null) {
                for (EventRecord event : task.events()) {
                    chars += 64 + length(event.sessionId()) + length(event.kind()) + length(event.text())
                            + (event.options() == null ? 0 : event.options().toString().length());
                }
            }
        }
        return (int) (chars * 1.2);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * 把历史文件权限向仅当前用户收紧：POSIX 平台直接 chmod 600；Windows 上移除“所有用户”
     * （BUILTIN\Users / Guests / Everyone）这类最宽泛的组授权，并确保属主始终保留完全控制。
     * <p>属主与 ACE 的主体名在不同令牌（含沙箱账户）下可能形式不一致，绝不能据此移除属主自己的
     * 授权，否则会锁死写入进程本身。失败只记录日志：权限收紧是加固项，不能阻断历史保存。</p>
     */
    private void restrictAccess(Path target) {
        if (target == null) return;
        try {
            if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(target, ownerOnly);
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
            if (acl == null) return;
            java.nio.file.attribute.UserPrincipal owner = acl.getOwner();
            List<AclEntry> kept = new ArrayList<>();
            boolean ownerCovered = false;
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW) continue;
                String name = entry.principal().getName();
                if (samePrincipal(entry.principal(), owner)) ownerCovered = true;
                // 仅移除“本机所有用户”级授权；Authenticated Users 等可能与当前进程令牌绑定，保留以免锁死自己
                if (name.endsWith("BUILTIN\\Users") || name.endsWith("BUILTIN\\Guests")
                        || name.endsWith("Everyone")) continue;
                kept.add(entry);
            }
            if (!ownerCovered && owner != null) {
                kept.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(AclEntryPermission.values())
                        .build());
            }
            if (kept.isEmpty()) return;
            acl.setAcl(kept);
        } catch (Exception failure) {
            LOG.debug("收紧 Agent 历史文件权限失败（可继续）: {}", failure.getMessage());
        }
    }

    private static boolean samePrincipal(java.nio.file.attribute.UserPrincipal entry,
                                        java.nio.file.attribute.UserPrincipal owner) {
        if (entry == null || owner == null) return false;
        return entry.equals(owner) || entry.getName().equalsIgnoreCase(owner.getName());
    }

    public static EventRecord toRecord(AgentActivityBus.Event event) {
        if (event == null) return null;
        long timestamp = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp().toEpochMilli();
        return new EventRecord(event.sessionId(),
                event.kind() == null ? null : event.kind().name(),
                event.text() == null ? "" : event.text(),
                timestamp,
                event.options() == null ? List.of() : List.copyOf(event.options()),
                event.proposalId());
    }

    /** 恢复的事件不带审批/回答回调，只用于展示历史。 */
    public static AgentActivityBus.Event toEvent(EventRecord record) {
        if (record == null || record.kind() == null) return null;
        AgentActivityBus.Kind kind;
        try {
            kind = AgentActivityBus.Kind.valueOf(record.kind());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        long timestamp = record.timestamp() > 0 ? record.timestamp() : System.currentTimeMillis();
        return new AgentActivityBus.Event(record.sessionId() == null ? "history" : record.sessionId(),
                kind,
                record.text() == null ? "" : record.text(),
                Instant.ofEpochMilli(timestamp),
                record.options() == null ? List.of() : List.copyOf(record.options()),
                null,
                null,
                record.proposalId());
    }
}
