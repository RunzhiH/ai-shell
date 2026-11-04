/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import org.fxmisc.richtext.InlineCssTextArea;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The terminal command field must keep normal Backspace/Delete editing. */
class TerminalInputDeletionTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void backspaceDeletesTextFromCommandInput() throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<TextField> inputRef = new AtomicReference<>();
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
            loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
            javafx.scene.Parent root = loader.load();
            TextField input = (TextField) loader.getNamespace().get("commandInput");
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 700, 500));
            stage.show();
            input.setText("abc");
            input.requestFocus();
            input.positionCaret(3);
            input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE,
                    false, false, false, false));
            inputRef.set(input);
            stageRef.set(stage);
        });
        fx(() -> {
            assertEquals("ab", inputRef.get().getText(), "Backspace must edit the command field");
            stageRef.get().close();
        });
    }

    @Test
    void backspaceInReadOnlyOutputDeletesFromCommandInput() throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<TextField> inputRef = new AtomicReference<>();
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-tab.fxml"));
            loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
            javafx.scene.Parent root = loader.load();
            TextField input = (TextField) loader.getNamespace().get("commandInput");
            InlineCssTextArea output = (InlineCssTextArea) loader.getNamespace().get("terminalOutput");
            output.setEditable(false);
            com.runzhi.aishell.terminal.TerminalInputBinder.forwardOutputEditingKeys(output, input);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 700, 500));
            stage.show();
            input.setText("abc");
            input.positionCaret(3);
            output.requestFocus();
            output.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE,
                    false, false, false, false));
            inputRef.set(input);
            stageRef.set(stage);
        });
        fx(() -> {
            assertEquals("ab", inputRef.get().getText(),
                    "Backspace typed while the read-only output is focused must edit the command input");
            stageRef.get().close();
        });
    }
    @FunctionalInterface
    private interface FxAction { void run() throws Exception; }

    private static void fx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
