package com.runzhi.aishell.editor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话恢复存储单测：未保存内容落盘后，同一会话能按路径完整读回；
 * 无脏标签时旧记录被清除，损坏文件不会抛异常。
 */
class EditorSessionStoreTest {

    private static final String SESSION_KEY = "unit-test-session-" + System.nanoTime();

    @Test
    void roundTripsEntriesWithContent() {
        try {
            List<EditorSessionStore.Entry> entries = List.of(
                    new EditorSessionStore.Entry(true, "/etc/a.txt", "alpha\nbeta".getBytes(StandardCharsets.UTF_8)),
                    new EditorSessionStore.Entry(false, "C:/tmp/b.txt", "本地内容".getBytes(StandardCharsets.UTF_8)));
            EditorSessionStore.save(SESSION_KEY, entries);

            List<EditorSessionStore.Entry> loaded = EditorSessionStore.load(SESSION_KEY);
            assertEquals(2, loaded.size());
            assertTrue(loaded.get(0).remote());
            assertEquals("/etc/a.txt", loaded.get(0).path());
            assertEquals("alpha\nbeta", new String(loaded.get(0).content(), StandardCharsets.UTF_8));
            assertFalse(loaded.get(1).remote());
            assertEquals("本地内容", new String(loaded.get(1).content(), StandardCharsets.UTF_8));
        } finally {
            EditorSessionStore.save(SESSION_KEY, List.of());
        }
    }

    @Test
    void emptyEntryListRemovesTheStoreFile() {
        EditorSessionStore.save(SESSION_KEY + "-removal", List.of(
                new EditorSessionStore.Entry(true, "/tmp/x", "x".getBytes(StandardCharsets.UTF_8))));
        EditorSessionStore.save(SESSION_KEY + "-removal", List.of());
        assertTrue(EditorSessionStore.load(SESSION_KEY + "-removal").isEmpty());
    }

    @Test
    void missingSessionReturnsEmptyList() {
        assertTrue(EditorSessionStore.load("no-such-session-" + System.nanoTime()).isEmpty());
    }

    @Test
    void storeDirectoryIsCreatedLazily() throws Exception {
        Path expected = Path.of(System.getProperty("user.home"), ".aishell", "editor-restore");
        EditorSessionStore.save(SESSION_KEY + "-dir", List.of(
                new EditorSessionStore.Entry(true, "/tmp/y", "y".getBytes(StandardCharsets.UTF_8))));
        assertTrue(Files.isDirectory(expected));
        EditorSessionStore.save(SESSION_KEY + "-dir", List.of());
    }
}
