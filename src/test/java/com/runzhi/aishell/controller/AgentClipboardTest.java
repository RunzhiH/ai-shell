/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Agent transcript copy buttons must put the rendered text on the system clipboard. */
class AgentClipboardTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void copyButtonCopiesMessageText() throws Exception {
        AtomicReference<Button> copyRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            view.printAssistant("复制内容");
            new Scene(view, 352, 600);
            view.applyCss();
            view.layout();
            copyRef.set((Button) view.lookupAll(".agent-copy-button").iterator().next());
        });
        fx(() -> {
            Button copy = copyRef.get();
            assertTrue(copy.getWidth() >= 20, "copy hit area: " + copy.getWidth());
            var bounds = copy.localToScene(copy.getBoundsInLocal());
            assertTrue(bounds.getMaxX() <= copy.getScene().getWidth() + 1, "copy outside scene: " + bounds);
        });
        fx(() -> copyRef.get().fire());
        fx(() -> assertEquals("复制内容", Clipboard.getSystemClipboard().getString()));
    }

    private static void fx(Runnable action) throws Exception {
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
