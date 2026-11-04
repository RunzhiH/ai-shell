/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 待发送的文件。remoteName 为远端文件名（不含路径分隔符），size/mtimeMillis 来自本地文件。
 */
public record ZModemOutFile(Path path, String remoteName, long size, long mtimeMillis) {

    public static ZModemOutFile of(Path file) {
        Path name = file.getFileName();
        return new ZModemOutFile(file, name == null ? "unnamed" : name.toString(),
                fileSize(file), file.toFile().lastModified());
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return -1;
        }
    }
}
