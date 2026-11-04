/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The Agent composer must keep normal Backspace editing. */
class AgentInputDeletionTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void backspaceDeletesAgentInputText() throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<TextArea> inputRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            TextArea input = (TextArea) field(view, "input");
            Stage stage = new Stage();
            stage.setScene(new Scene(view, 352, 600));
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
            assertEquals("ab", inputRef.get().getText(), "Backspace must edit the Agent composer");
            stageRef.get().close();
        });
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
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
