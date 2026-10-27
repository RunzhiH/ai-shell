/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Manages a recoverable per-user Windows Startup-folder launcher. */
public final class WindowsAutoStartManager {
    private static final String FILE_NAME = "AIShell.cmd";

    public boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public boolean isEnabled() {
        return isSupported() && Files.isRegularFile(startupFile());
    }

    public void setEnabled(boolean enabled) throws IOException {
        if (!isSupported()) {
            if (enabled) throw new IOException("当前操作系统不支持 Windows 开机启动项");
            return;
        }
        Path target = startupFile();
        if (!enabled) {
            Files.deleteIfExists(target);
            return;
        }
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command().orElseThrow(() -> new IOException("无法确定 AIShell 启动命令"));
        String[] arguments = info.arguments().orElse(new String[0]);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temporary, buildLauncher(command, arguments), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String buildLauncher(String command, String[] arguments) {
        StringBuilder line = new StringBuilder("@echo off\r\nstart \"\" ").append(quote(command));
        for (String argument : arguments) line.append(' ').append(quote(argument));
        return line.append("\r\n").toString();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("%", "%%").replace("\"", "\"\"");
        return '"' + safe + '"';
    }

    private Path startupFile() {
        String appData = System.getenv("APPDATA");
        Path roaming = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming") : Path.of(appData);
        return roaming.resolve("Microsoft").resolve("Windows").resolve("Start Menu")
                .resolve("Programs").resolve("Startup").resolve(FILE_NAME);
    }
}
