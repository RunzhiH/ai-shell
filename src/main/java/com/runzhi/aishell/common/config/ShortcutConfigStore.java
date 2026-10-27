/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.scene.input.KeyCombination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/** Persistent application-level shortcuts shared by settings and the terminal panel. */
public final class ShortcutConfigStore {
    private static final Preferences PREFS = Preferences.userRoot().node("/com/runzhi/aishell/shortcuts");
    private static final Gson GSON = new Gson();

    public static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("新建终端标签", "Ctrl+T");
        values.put("关闭标签", "Ctrl+W");
        values.put("重新连接", "Ctrl+Shift+R");
        values.put("清屏", "Ctrl+Shift+L");
        values.put("打开设置", "Ctrl+Comma");
        return values;
    }

    public static String get(String action) { return PREFS.get(action, defaults().getOrDefault(action, "")); }
    public static void set(String action, String shortcut) { PREFS.put(action, shortcut == null ? "" : shortcut); }
    public static void reset() { defaults().forEach(ShortcutConfigStore::set); }

    public static boolean matches(String action, javafx.scene.input.KeyEvent event) {
        String value = get(action);
        if (value == null || value.isBlank()) return false;
        try { return KeyCombination.valueOf(value.replace("Comma", ",")).match(event); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public static void exportTo(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        defaults().keySet().forEach(action -> values.put(action, get(action)));
        Files.writeString(file, GSON.toJson(values), StandardCharsets.UTF_8);
    }

    public static void importFrom(Path file) throws IOException {
        Map<String, String> values = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
        if (values == null) throw new IllegalArgumentException("快捷键文件为空");
        for (String action : defaults().keySet()) {
            if (!values.containsKey(action)) continue;
            String shortcut = values.get(action);
            if (shortcut != null && !shortcut.isBlank()) KeyCombination.valueOf(shortcut.replace("Comma", ","));
            set(action, shortcut);
        }
    }

    private ShortcutConfigStore() {}
}
