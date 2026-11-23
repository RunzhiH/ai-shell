package com.runzhi.aishell.editor;

import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EditorPreferences 单测（方案 §9）：默认值与越界值回退。
 */
class EditorPreferencesTest {

    private static final Preferences PREFS = Preferences.userNodeForPackage(EditorPreferences.class);

    @Test
    void defaultsMatchThePlan() {
        try {
            for (String key : new String[]{"editor.openMode", "editor.externalCommand", "editor.autoUpload",
                    "editor.maxInlineBytes", "editor.maxExternalBytes", "editor.fallbackEncoding",
                    "editor.tempRetentionDays", "editor.restoreSession"}) {
                PREFS.remove(key);
            }
            assertEquals(EditorPreferences.OpenMode.BUILTIN, EditorPreferences.openMode());
            assertEquals("", EditorPreferences.externalCommand());
            assertTrue(EditorPreferences.autoUpload());
            assertEquals(2 * 1024 * 1024, EditorPreferences.maxInlineBytes());
            assertEquals(50L * 1024 * 1024, EditorPreferences.maxExternalBytes());
            assertEquals("GBK", EditorPreferences.fallbackEncoding());
            assertEquals(7, EditorPreferences.tempRetentionDays());
            assertTrue(EditorPreferences.restoreSession());
        } finally {
            // 不污染用户偏好：删除测试用键
            for (String key : new String[]{"editor.openMode", "editor.externalCommand", "editor.autoUpload",
                    "editor.maxInlineBytes", "editor.maxExternalBytes", "editor.fallbackEncoding",
                    "editor.tempRetentionDays", "editor.restoreSession"}) {
                PREFS.remove(key);
            }
        }
    }

    @Test
    void outOfRangeValuesFallBackToClampedBounds() {
        int savedInline = EditorPreferences.maxInlineBytes();
        long savedExternal = EditorPreferences.maxExternalBytes();
        int savedRetention = EditorPreferences.tempRetentionDays();
        try {
            EditorPreferences.setMaxInlineBytes(0);
            assertEquals(1024, EditorPreferences.maxInlineBytes());
            EditorPreferences.setMaxInlineBytes(Integer.MAX_VALUE);
            assertEquals(64 * 1024 * 1024, EditorPreferences.maxInlineBytes());

            EditorPreferences.setMaxExternalBytes(10);
            assertEquals(1024 * 1024, EditorPreferences.maxExternalBytes());
            EditorPreferences.setMaxExternalBytes(Integer.MAX_VALUE);
            assertEquals(512L * 1024 * 1024, EditorPreferences.maxExternalBytes());

            EditorPreferences.setTempRetentionDays(0);
            assertEquals(1, EditorPreferences.tempRetentionDays());
            EditorPreferences.setTempRetentionDays(365);
            assertEquals(90, EditorPreferences.tempRetentionDays());
        } finally {
            EditorPreferences.setMaxInlineBytes(savedInline);
            EditorPreferences.setMaxExternalBytes((int) savedExternal);
            EditorPreferences.setTempRetentionDays(savedRetention);
        }
    }

    @Test
    void openModeRoundTripsThroughCanonicalValues() {
        EditorPreferences.OpenMode saved = EditorPreferences.openMode();
        try {
            EditorPreferences.setOpenMode(EditorPreferences.OpenMode.EXTERNAL);
            assertEquals(EditorPreferences.OpenMode.EXTERNAL, EditorPreferences.openMode());
            EditorPreferences.setOpenMode(EditorPreferences.OpenMode.ASK);
            assertEquals(EditorPreferences.OpenMode.ASK, EditorPreferences.openMode());
            EditorPreferences.setOpenMode(EditorPreferences.OpenMode.BUILTIN);
            assertEquals(EditorPreferences.OpenMode.BUILTIN, EditorPreferences.openMode());
        } finally {
            EditorPreferences.setOpenMode(saved);
        }
    }

    @Test
    void externalCommandIsTrimmedAndNullable() {
        String saved = EditorPreferences.externalCommand();
        try {
            EditorPreferences.setExternalCommand("  code {file}  ");
            assertEquals("code {file}", EditorPreferences.externalCommand());
            EditorPreferences.setExternalCommand(null);
            assertEquals("", EditorPreferences.externalCommand());
        } finally {
            EditorPreferences.setExternalCommand(saved);
        }
    }
}
