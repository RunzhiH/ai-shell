/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal;

import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.InlineCssTextArea;

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

    /**
     * 输出区是只读历史，但打印字符会转发到命令输入。删除键必须同样转发，
     * 否则焦点在输出区时只能追加、不能退格。
     */
    public static void forwardOutputEditingKeys(InlineCssTextArea output, TextField input) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(input, "input");
        output.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                    && event.getCharacter() != null && !event.getCharacter().isEmpty()
                    && event.getCharacter().chars().allMatch(ch -> ch >= 0x20 && ch != 0x7f)) {
                input.appendText(event.getCharacter());
                input.requestFocus();
                input.positionCaret(input.getLength());
                event.consume();
            }
        });
        output.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.BACK_SPACE || event.getCode() == KeyCode.DELETE) {
                // 输出区转发字符时总是追加到命令末尾；删除也定位到末尾，
                // 避免 TextField 失焦后 caret 归零导致退格无效或误删开头。
                input.requestFocus();
                input.positionCaret(input.getLength());
                if (event.getCode() == KeyCode.BACK_SPACE) input.deletePreviousChar();
                else input.deleteNextChar();
                event.consume();
            }
        });
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
