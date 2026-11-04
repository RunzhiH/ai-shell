/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Command suggestions must behave like executable actions, not just insert text. */
class AgentCommandSuggestionTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void clickingZeroArgumentCommandExecutesIt() throws Exception {
        List<String> submitted = new ArrayList<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(
                    List.of(new AgentConsoleView.Command("/help", "help"),
                            new AgentConsoleView.Command("/tasks", "tasks")),
                    submitted::add, () -> {}, () -> {});
            TextArea input = (TextArea) field(view, "input");
            input.setText("/");
            invoke(view, "acceptSuggestion");
            assertEquals(List.of("/help"), submitted, "点击无参数命令应立即执行");
            assertEquals("", input.getText());
        });
    }

    @Test
    void clickingArgumentCommandInsertsUsageWithoutExecuting() throws Exception {
        List<String> submitted = new ArrayList<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(
                    List.of(new AgentConsoleView.Command("/resume", "resume")),
                    submitted::add, () -> {}, () -> {});
            TextArea input = (TextArea) field(view, "input");
            input.setText("/res");
            invoke(view, "acceptSuggestion");
            assertEquals("/resume ", input.getText(), "带参数命令应填入并等待用户补参数");
            assertTrue(submitted.isEmpty());
        });
    }

    private static void invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
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
