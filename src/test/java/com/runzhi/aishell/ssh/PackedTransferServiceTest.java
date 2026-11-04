/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PackedTransferServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void zipsDirectoryPreservingStructure() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("project"));
        Path sub = Files.createDirectories(root.resolve("src/main"));
        Files.write(root.resolve("README.md"), "hello".getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[10_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        Files.write(sub.resolve("App.java"), payload);

        PackedTransferService service = new PackedTransferService();
        List<Long> progress = new ArrayList<>();
        Path archive = service.zipDirectory(root, progress::add);
        try {
            assertTrue(Files.isRegularFile(archive));
            List<String> names = new ArrayList<>();
            byte[] readBack;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    names.add(entries.nextElement().getName());
                }
                try (var in = zip.getInputStream(zip.getEntry("src/main/App.java"))) {
                    readBack = in.readAllBytes();
                }
            }
            assertTrue(names.contains("README.md"), () -> "missing README.md in " + names);
            assertTrue(names.contains("src/main/App.java"), () -> "missing App.java in " + names);
            assertArrayEquals(payload, readBack);
            assertFalse(progress.isEmpty(), "progress callback should be invoked");
            // README.md 5 字节 + App.java 10000 字节
            assertEquals(10_005L, progress.get(progress.size() - 1));
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void rejectsNonDirectory() {
        PackedTransferService service = new PackedTransferService();
        Path file = tempDir.resolve("plain.txt");
        assertDoesNotThrow(() -> Files.writeString(file, "x"));
        assertThrows(Exception.class, () -> service.zipDirectory(file, null));
    }

    @Test
    void quotesPathsForShell() {
        assertEquals("'plain'", PackedTransferService.shellQuote("plain"));
        assertEquals("'a b'", PackedTransferService.shellQuote("a b"));
        assertEquals("'it'\\''s'", PackedTransferService.shellQuote("it's"));
    }

    @Test
    void extractionCommandsTryCommonTools() {
        List<String> commands = PackedTransferService.extractionCommands("/data/a.zip", "/data/out");
        assertTrue(commands.get(0).contains("unzip -o -q"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("busybox unzip")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("python3 -m zipfile")));
        assertTrue(commands.stream().allMatch(c -> c.contains(PackedTransferService.UNPACK_MARKER)));
        assertTrue(commands.stream().allMatch(c -> c.contains("'/data/a.zip'")));
        assertTrue(commands.stream().allMatch(c -> c.contains("'/data/out'")));
    }

    @Test
    void extractionSucceedsWhenAnyCommandWorks() {
        PackedTransferService service = new PackedTransferService();
        List<String> executed = new ArrayList<>();
        // 前两条不可用，第三条成功
        Function<String, String> runner = command -> {
            executed.add(command);
            if (command.contains("unzip -o -q") || command.contains("busybox")) {
                return "unzip: command not found";
            }
            return "output\n" + PackedTransferService.UNPACK_MARKER;
        };
        assertTrue(service.extractRemotely(runner, "/tmp/a.zip", "/tmp/out"));
        assertEquals(3, executed.size(), "should stop at the first successful extractor");
    }

    @Test
    void extractionFailsWhenNoToolAvailable() {
        PackedTransferService service = new PackedTransferService();
        Function<String, String> runner = command -> "command not found";
        assertFalse(service.extractRemotely(runner, "/tmp/a.zip", "/tmp/out"));
    }

    @Test
    void extractionSurvivesRunnerExceptions() {
        PackedTransferService service = new PackedTransferService();
        Function<String, String> runner = command -> {
            throw new IllegalStateException("ssh dropped");
        };
        assertFalse(service.extractRemotely(runner, "/tmp/a.zip", "/tmp/out"));
    }
}
