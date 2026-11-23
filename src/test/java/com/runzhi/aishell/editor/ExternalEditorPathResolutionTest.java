package com.runzhi.aishell.editor;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部编辑器 PATH 解析回归：Windows 上不能选中无扩展名的脚本
 * （VS Code bin/code 是 shell 脚本，CreateProcess 会以错误 193 拒绝），
 * 应按 PATHEXT 优先解析 .cmd/.exe；命令本身带扩展名时不再追加。
 */
class ExternalEditorPathResolutionTest {

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void windowsPrefersExtensionedExecutableOverBareScript() throws IOException {
        Path dir = Files.createTempDirectory("dsh-editor-path");
        Files.writeString(dir.resolve("code"), "#!/bin/sh\necho hi\n");
        Files.writeString(dir.resolve("code.cmd"), "@echo off\r\nexit /b 0\r\n");

        Path resolved = ExternalEditorDetector.resolveIn(dir.toString(), "code");
        if (onWindows()) {
            assertEquals(dir.resolve("code.cmd"), resolved, "Windows 应选中 code.cmd 而非无扩展名脚本");
        } else {
            assertEquals(dir.resolve("code"), resolved, "类 Unix 系统应直接使用无扩展名条目");
        }
    }

    @Test
    void windowsPicksExeOverCmdWhenBothExist() throws IOException {
        Path dir = Files.createTempDirectory("dsh-editor-path");
        Files.writeString(dir.resolve("tool.cmd"), "@echo off\r\nexit /b 0\r\n");
        Files.write(dir.resolve("tool.exe"), new byte[]{0});

        Path resolved = ExternalEditorDetector.resolveIn(dir.toString() + File.pathSeparator + dir.toString(), "tool");
        if (onWindows()) {
            assertEquals(dir.resolve("tool.exe"), resolved, "PATHEXT 顺序中 .EXE 应优先于 .CMD");
        } else {
            assertNull(resolved, "类 Unix 系统上无扩展名条目不应被扩展名匹配");
        }
    }

    @Test
    void commandWithExtensionIsResolvedAsIs() throws IOException {
        Path dir = Files.createTempDirectory("dsh-editor-path");
        Files.writeString(dir.resolve("code.cmd"), "@echo off\r\nexit /b 0\r\n");

        Path resolved = ExternalEditorDetector.resolveIn(dir.toString(), "code.cmd");
        assertEquals(dir.resolve("code.cmd"), resolved, "已带扩展名的命令不应再被追加扩展名");
    }

    @Test
    void missingCommandResolvesToNull() throws IOException {
        Path dir = Files.createTempDirectory("dsh-editor-path");
        assertNull(ExternalEditorDetector.resolveIn(dir.toString(), "definitely-not-installed-12345"));
        assertNull(ExternalEditorDetector.resolveIn(null, "code"));
        assertNull(ExternalEditorDetector.resolveIn("", "code"));
    }

    @Test
    void blankPathEntriesAreSkipped() throws IOException {
        Path dir = Files.createTempDirectory("dsh-editor-path");
        Files.writeString(dir.resolve("code.cmd"), "@echo off\r\nexit /b 0\r\n");
        Path resolved = ExternalEditorDetector.resolveIn(
                File.pathSeparator + dir + File.pathSeparator, "code");
        if (onWindows()) {
            assertEquals(dir.resolve("code.cmd"), resolved);
        } else {
            assertNull(resolved);
        }
    }
}
