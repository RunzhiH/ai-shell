/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import com.runzhi.aishell.terminal.zmodem.ZModemSupport;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * ZMODEM 会话与终端标签界面的桥接：下载目录、发送文件选择、进度浮层与取消状态。
 */
public final class TerminalZModemSupport implements ZModemSupport {

    private static final Preferences PREFS = Preferences.userNodeForPackage(TerminalZModemSupport.class);
    private static final String DOWNLOAD_DIR_KEY = "zmodem.downloadDir";

    private final Tab tab;
    private final ZModemOverlay overlay;
    private volatile boolean cancelled;
    private volatile String lastSavedFile;

    public TerminalZModemSupport(Tab tab, ZModemOverlay overlay) {
        this.tab = tab;
        this.overlay = overlay;
        overlay.setCancelAction(this::requestCancel);
    }

    /** 用户点击浮层取消按钮。 */
    public void requestCancel() {
        cancelled = true;
    }

    @Override
    public Path downloadDirectory() {
        String stored = PREFS.get(DOWNLOAD_DIR_KEY, "");
        Path dir = stored.isBlank() ? defaultDownloadDirectory() : Path.of(stored);
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException e) {
            dir = Path.of(System.getProperty("user.home"));
        }
        return dir;
    }

    private static Path defaultDownloadDirectory() {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        return Files.isDirectory(downloads)
                ? downloads.resolve("ai-shell")
                : Path.of(System.getProperty("user.home"), "aishell-downloads");
    }

    @Override
    public List<Path> chooseFilesToSend() {
        CompletableFuture<List<Path>> choice = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("选择要通过 ZMODEM 发送的文件");
                    List<File> files = chooser.showOpenMultipleDialog(null);
                    if (files == null || files.isEmpty()) {
                        choice.complete(List.of());
                    } else {
                        choice.complete(files.stream().map(File::toPath).toList());
                    }
                } catch (RuntimeException e) {
                    choice.complete(List.of());
                }
            });
        } catch (IllegalStateException e) {
            // 无 JavaFX 环境（如自动化测试）：视为取消
            return List.of();
        }
        try {
            return choice.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void onFileStart(String direction, String fileName, long size) {
        if ("下载".equals(direction)) {
            lastSavedFile = fileName;
        }
        Platform.runLater(() -> overlay.begin(direction, fileName, size));
    }

    @Override
    public void onProgress(long transferred, long total) {
        Platform.runLater(() -> overlay.update(transferred, total));
    }

    @Override
    public void onFileDone(String fileName, boolean success) {
        // 单个文件完成由会话结束统一提示
    }

    @Override
    public void onSessionFinished(boolean success, String message) {
        String notice;
        if (success) {
            notice = message != null ? message
                    : (lastSavedFile != null
                        ? "ZMODEM 传输完成，文件已保存到 " + downloadDirectory()
                        : "ZMODEM 传输完成");
        } else {
            notice = message != null ? "ZMODEM " + message : "ZMODEM 传输未完成";
        }
        Platform.runLater(() -> {
            overlay.end();
            InteractiveTerminalView.writeFeedback(tab, notice);
        });
        lastSavedFile = null;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void resetCancel() {
        cancelled = false;
    }
}
