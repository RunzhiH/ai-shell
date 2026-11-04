/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * 终端标签内的 ZMODEM 传输浮层：文件名、进度、速度与取消按钮。
 * 仅在传输期间可见。
 */
public final class ZModemOverlay extends HBox {

    private static final String PANEL_STYLE =
            "-fx-background-color: rgba(22, 27, 34, 0.94); -fx-border-color: #30363d; "
                    + "-fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12 8 12;";

    private final Label title = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label detail = new Label();
    private final Button cancel = new Button("取消");

    private long lastBytes;
    private long lastNanos;

    public ZModemOverlay() {
        super(10);
        setId("zmodemOverlay");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6));
        setMaxWidth(560);
        setStyle(PANEL_STYLE);
        setVisible(false);
        setManaged(false);

        title.setStyle("-fx-text-fill: #e6edf3; -fx-font-weight: bold;");
        detail.setStyle("-fx-text-fill: #8b949e;");
        progress.setPrefWidth(220);
        progress.setStyle("-fx-accent: #2f81f7;");
        cancel.setStyle("-fx-background-color: #21262d; -fx-text-fill: #e6edf3; "
                + "-fx-border-color: #30363d; -fx-border-radius: 4;");

        HBox.setHgrow(progress, Priority.SOMETIMES);
        getChildren().addAll(title, progress, detail, cancel);
    }

    public void setCancelAction(Runnable action) {
        cancel.setOnAction(event -> action.run());
    }

    /** 传输开始：显示浮层并复位进度。 */
    public void begin(String direction, String fileName, long size) {
        title.setText(direction + " · " + fileName);
        detail.setText(size >= 0 ? formatSize(size) : "大小未知");
        progress.setProgress(-1);
        lastBytes = 0;
        lastNanos = System.nanoTime();
        cancel.setDisable(false);
        setVisible(true);
        setManaged(true);
    }

    /** 传输进度更新。 */
    public void update(long transferred, long total) {
        if (total > 0) {
            progress.setProgress(Math.min(1.0, (double) transferred / total));
        }
        long now = System.nanoTime();
        long elapsed = now - lastNanos;
        String speed = "";
        if (elapsed > 200_000_000L) {
            double bytesPerSecond = (transferred - lastBytes) * 1_000_000_000.0 / elapsed;
            speed = formatSize((long) bytesPerSecond) + "/s";
            lastBytes = transferred;
            lastNanos = now;
        }
        String sizeText = total > 0
                ? formatSize(transferred) + " / " + formatSize(total)
                : formatSize(transferred);
        String percent = total > 0
                ? String.format(" (%.0f%%)", Math.min(100.0, transferred * 100.0 / total))
                : "";
        detail.setText(speed.isEmpty() ? sizeText + percent : sizeText + percent + " · " + speed);
    }

    /** 结束：隐藏浮层。 */
    public void end() {
        setVisible(false);
        setManaged(false);
        progress.setProgress(0);
        cancel.setDisable(true);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }
}
