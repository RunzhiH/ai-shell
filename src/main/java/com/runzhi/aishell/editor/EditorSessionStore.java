/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 编辑器会话恢复存储（方案 §4.5 editor.restoreSession）：
 * 把窗口里未保存的标签（路径 + 内容字节）落到 JSON，下次打开同一会话时回填为脏文档。
 *
 * <p>只记录路径与内容，不记录连接参数；文件在远端被删除或无可读权限时恢复静默跳过。
 */
@Slf4j
public final class EditorSessionStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".aishell", "editor-restore");

    public record Entry(boolean remote, String path, byte[] content) {
    }

    private EditorSessionStore() {
    }

    /** 保存指定会话的恢复条目；条目为空时删除旧记录。 */
    public static void save(String sessionKey, List<Entry> entries) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        try {
            Files.createDirectories(STORE_DIR);
            Path file = STORE_DIR.resolve(safeName(sessionKey) + ".json");
            if (entries == null || entries.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            JsonArray array = new JsonArray();
            for (Entry entry : entries) {
                JsonObject object = new JsonObject();
                object.addProperty("remote", entry.remote());
                object.addProperty("path", entry.path());
                object.addProperty("content", Base64.getEncoder().encodeToString(entry.content()));
                array.add(object);
            }
            Files.writeString(file, new Gson().toJson(array), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            log.warn("Failed to persist editor session restore: {}", failure.getMessage());
        }
    }

    /** 读取指定会话的恢复条目；无记录或解析失败时返回空列表。 */
    public static List<Entry> load(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) return List.of();
        try {
            Path file = STORE_DIR.resolve(safeName(sessionKey) + ".json");
            if (!Files.isRegularFile(file)) return List.of();
            JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!element.isJsonArray()) return List.of();
            List<Entry> entries = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                JsonObject object = item.getAsJsonObject();
                boolean remote = object.has("remote") && object.get("remote").getAsBoolean();
                String path = object.has("path") ? object.get("path").getAsString() : null;
                if (path == null || path.isBlank() || !object.has("content")) continue;
                byte[] content = Base64.getDecoder().decode(object.get("content").getAsString());
                entries.add(new Entry(remote, path, content));
            }
            return entries;
        } catch (Exception failure) {
            log.warn("Failed to load editor session restore: {}", failure.getMessage());
            return List.of();
        }
    }

    private static String safeName(String sessionKey) {
        return sessionKey.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
