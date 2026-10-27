/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;

import java.util.Objects;

/** Binds the dynamic terminal input bar independently of its optional FXML controller. */
public final class TerminalInputBinder {
    private TerminalInputBinder() { }

    public static void bind(TextField input, ChoiceBox<String> modeChoice, Button executeButton,
                            Button reconnectButton, Runnable submit, Runnable reconnect) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(modeChoice, "modeChoice");
        Objects.requireNonNull(submit, "submit");
        if (!modeChoice.getItems().contains("普通模式")) modeChoice.getItems().add("普通模式");
        if (!modeChoice.getItems().contains("AI模式")) modeChoice.getItems().add("AI模式");
        if (modeChoice.getValue() == null) modeChoice.setValue("普通模式");

        input.setOnAction(event -> submit.run());
        if (executeButton != null) executeButton.setOnAction(event -> submit.run());
        if (reconnectButton != null && reconnect != null) reconnectButton.setOnAction(event -> reconnect.run());
        modeChoice.valueProperty().addListener((observable, oldValue, newValue) -> applyMode(input, executeButton, newValue));
        applyMode(input, executeButton, modeChoice.getValue());
    }

    private static void applyMode(TextField input, Button executeButton, String mode) {
        boolean aiMode = "AI模式".equals(mode) || "AI 模式".equals(mode);
        input.setPromptText(aiMode ? "描述目标，AI 将读取当前会话并提出待确认命令…" : "输入远程命令…");
        input.setStyle(aiMode
                ? "-fx-font-family: 'Cascadia Code', 'Consolas', monospace; -fx-font-size: 12px; "
                    + "-fx-control-inner-background: #172033; -fx-text-fill: #b7d7ff; "
                    + "-fx-prompt-text-fill: #7893b8; -fx-border-color: #388bfd;"
                : "-fx-font-family: 'Cascadia Code', 'Consolas', monospace; -fx-font-size: 12px; "
                    + "-fx-control-inner-background: #0d1117; -fx-text-fill: #f0f6fc; "
                    + "-fx-prompt-text-fill: #8b949e; -fx-border-color: #30363d;");
        if (executeButton != null) executeButton.setText(aiMode ? "提交给 AI" : "执行");
    }
}
