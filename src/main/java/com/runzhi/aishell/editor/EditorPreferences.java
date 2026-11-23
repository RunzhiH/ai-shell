/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import java.util.prefs.Preferences;

/**
 * 编辑器设置项的统一读写（方案 §4.5）。
 *
 * <p>沿用设置页 Preferences 模式，不合法值回退默认值，绝不抛异常。
 */
public final class EditorPreferences {

    public enum OpenMode { BUILTIN, EXTERNAL, ASK }

    private static final Preferences PREFS = Preferences.userNodeForPackage(EditorPreferences.class);

    private static final String KEY_OPEN_MODE = "editor.openMode";
    private static final String KEY_EXTERNAL_COMMAND = "editor.externalCommand";
    private static final String KEY_AUTO_UPLOAD = "editor.autoUpload";
    private static final String KEY_MAX_INLINE_BYTES = "editor.maxInlineBytes";
    private static final String KEY_MAX_EXTERNAL_BYTES = "editor.maxExternalBytes";
    private static final String KEY_FALLBACK_ENCODING = "editor.fallbackEncoding";
    private static final String KEY_TEMP_RETENTION_DAYS = "editor.tempRetentionDays";
    private static final String KEY_RESTORE_SESSION = "editor.restoreSession";
    /** 用户在编辑器选择框中记住的首选外部编辑器名称（未设置时按检测顺序自动选择）。 */
    private static final String KEY_PREFERRED_EDITOR = "editor.preferredEditorName";

    private static final String DEFAULT_FALLBACK_ENCODING = "GBK";
    private static final int MIN_INLINE_BYTES = 1024;
    private static final int MAX_INLINE_BYTES = 64 * 1024 * 1024;
    private static final int MIN_EXTERNAL_BYTES = 1024 * 1024;
    private static final int MAX_EXTERNAL_BYTES = 512 * 1024 * 1024;
    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 90;

    private EditorPreferences() {
    }

    public static OpenMode openMode() {
        return parseOpenMode(PREFS.get(KEY_OPEN_MODE, OpenMode.BUILTIN.name()));
    }

    public static void setOpenMode(OpenMode mode) {
        PREFS.put(KEY_OPEN_MODE, (mode == null ? OpenMode.BUILTIN : mode).name());
    }

    private static OpenMode parseOpenMode(String stored) {
        if (stored == null) return OpenMode.BUILTIN;
        return switch (stored.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "EXTERNAL" -> OpenMode.EXTERNAL;
            case "ASK" -> OpenMode.ASK;
            default -> OpenMode.BUILTIN;
        };
    }

    /** 自定义外部编辑器命令模板（含 {file}）；空表示自动检测。 */
    public static String externalCommand() {
        return PREFS.get(KEY_EXTERNAL_COMMAND, "");
    }

    public static void setExternalCommand(String command) {
        if (command == null) PREFS.remove(KEY_EXTERNAL_COMMAND);
        else PREFS.put(KEY_EXTERNAL_COMMAND, command.trim());
    }

    /** 外部编辑器保存后是否自动回传（默认 true）。 */
    public static boolean autoUpload() {
        return PREFS.getBoolean(KEY_AUTO_UPLOAD, true);
    }

    public static void setAutoUpload(boolean autoUpload) {
        PREFS.putBoolean(KEY_AUTO_UPLOAD, autoUpload);
    }

    /** 内置编辑器字节数上限，默认 2MB。 */
    public static int maxInlineBytes() {
        return clamp(PREFS.getInt(KEY_MAX_INLINE_BYTES, 2 * 1024 * 1024),
                MIN_INLINE_BYTES, MAX_INLINE_BYTES);
    }

    public static void setMaxInlineBytes(int bytes) {
        PREFS.putInt(KEY_MAX_INLINE_BYTES, clamp(bytes, MIN_INLINE_BYTES, MAX_INLINE_BYTES));
    }

    /** 外部编辑器字节数上限，默认 50MB。 */
    public static int maxExternalBytes() {
        return clamp(PREFS.getInt(KEY_MAX_EXTERNAL_BYTES, 50 * 1024 * 1024),
                MIN_EXTERNAL_BYTES, MAX_EXTERNAL_BYTES);
    }

    public static void setMaxExternalBytes(int bytes) {
        PREFS.putInt(KEY_MAX_EXTERNAL_BYTES, clamp(bytes, MIN_EXTERNAL_BYTES, MAX_EXTERNAL_BYTES));
    }

    /** UTF-8 严格解码失败时的回退编码，默认 GBK。 */
    public static String fallbackEncoding() {
        return PREFS.get(KEY_FALLBACK_ENCODING, DEFAULT_FALLBACK_ENCODING);
    }

    public static void setFallbackEncoding(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            PREFS.put(KEY_FALLBACK_ENCODING, DEFAULT_FALLBACK_ENCODING);
        } else {
            PREFS.put(KEY_FALLBACK_ENCODING, encoding.trim());
        }
    }

    /** 外部编辑器临时文件保留天数，默认 7。 */
    public static int tempRetentionDays() {
        return clamp(PREFS.getInt(KEY_TEMP_RETENTION_DAYS, 7), MIN_RETENTION_DAYS, MAX_RETENTION_DAYS);
    }

    public static void setTempRetentionDays(int days) {
        PREFS.putInt(KEY_TEMP_RETENTION_DAYS, clamp(days, MIN_RETENTION_DAYS, MAX_RETENTION_DAYS));
    }

    /** 会话恢复开关（多标签未保存内容），默认 true。 */
    public static boolean restoreSession() {
        return PREFS.getBoolean(KEY_RESTORE_SESSION, true);
    }

    public static void setRestoreSession(boolean restore) {
        PREFS.putBoolean(KEY_RESTORE_SESSION, restore);
    }

    /** 已记住的首选外部编辑器名称；null 表示尚未选择、按检测顺序自动取第一个。 */
    public static String preferredEditorName() {
        return PREFS.get(KEY_PREFERRED_EDITOR, null);
    }

    public static void setPreferredEditorName(String name) {
        if (name == null || name.isBlank()) PREFS.remove(KEY_PREFERRED_EDITOR);
        else PREFS.put(KEY_PREFERRED_EDITOR, name);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }
}
