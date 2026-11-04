package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.config.ShortcutConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Settings drafts and malformed imports must not leak changes into live shortcuts. */
class ShortcutSettingsDraftTest {
    @TempDir Path directory;
    private Map<String,String> original;
    @BeforeEach void backup() { original = ShortcutConfigStore.snapshot(); ShortcutConfigStore.reset(); }
    @AfterEach void restore() { original.forEach(ShortcutConfigStore::set); }

    @SuppressWarnings("unchecked")
    private Map<String,String> draft(SettingsShortcutsController controller) throws Exception {
        Field field = SettingsShortcutsController.class.getDeclaredField("draft");
        field.setAccessible(true); return (Map<String,String>)field.get(controller);
    }
    @Test void closingWithoutSaveDiscardsEditedDraft() throws Exception {
        SettingsShortcutsController controller = new SettingsShortcutsController();
        draft(controller).put("新建终端标签","Alt+T");
        assertEquals("Ctrl+T",ShortcutConfigStore.get("新建终端标签"));
        assertEquals("Ctrl+T",draft(new SettingsShortcutsController()).get("新建终端标签"));
    }
    @Test void saveAppliesCurrentDraft() throws Exception {
        SettingsShortcutsController controller = new SettingsShortcutsController();
        draft(controller).put("新建终端标签","Alt+T");
        controller.saveSettings();
        assertEquals("Alt+T",ShortcutConfigStore.get("新建终端标签"));
    }
    @Test void invalidLaterEntryDoesNotPartiallyImport() throws Exception {
        Path file = directory.resolve("invalid.json");
        Files.writeString(file,"{\"新建终端标签\":\"Alt+T\",\"打开设置\":\"Ctrl+Ctrl+T\"}");
        Map<String,String> before = ShortcutConfigStore.snapshot();
        assertThrows(IllegalArgumentException.class,()->ShortcutConfigStore.importFrom(file));
        assertEquals(before,ShortcutConfigStore.snapshot());
    }
    @Test void equivalentKeyCombinationsAreConflictsAndNothingIsSaved() throws Exception {
        SettingsShortcutsController controller = new SettingsShortcutsController();
        draft(controller).put("新建终端标签","Ctrl+Shift+T");
        draft(controller).put("关闭标签","Shift+Ctrl+T");
        Map<String,String> before = ShortcutConfigStore.snapshot();
        assertThrows(IllegalArgumentException.class,controller::saveSettings);
        assertEquals(before,ShortcutConfigStore.snapshot());
    }
    @Test void partialImportKeepsOtherUnsavedEdits() throws Exception {
        Path file = directory.resolve("partial.json");
        Files.writeString(file,"{\"关闭标签\":\"Alt+W\"}");
        Map<String,String> draft = new LinkedHashMap<>(ShortcutConfigStore.snapshot());
        draft.put("新建终端标签","Alt+T");
        Map<String,String> imported = ShortcutConfigStore.readFrom(file,draft);
        assertEquals("Alt+T",imported.get("新建终端标签"));
        assertEquals("Alt+W",imported.get("关闭标签"));
        assertEquals("Ctrl+W",ShortcutConfigStore.get("关闭标签"));
    }
    @Test void readAndExportUseDraftWithoutApplying() throws Exception {
        Path file = directory.resolve("draft.json");
        Map<String,String> values = new LinkedHashMap<>(ShortcutConfigStore.snapshot());
        values.put("新建终端标签","Alt+T");
        ShortcutConfigStore.exportTo(file,values);
        assertEquals("Alt+T",ShortcutConfigStore.readFrom(file).get("新建终端标签"));
        assertEquals("Ctrl+T",ShortcutConfigStore.get("新建终端标签"));
    }
}
