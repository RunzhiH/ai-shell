/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentHistoryStore;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ui.AgentConsoleView;
import com.runzhi.aishell.ui.AgentHistoryDialog;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 参数补全、可恢复错误卡片与历史面板过滤的视图级行为验证。 */
class AgentConsoleCompletionTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void typingCommandWithSpaceShowsArgumentCandidates() throws Exception {
        List<String> submitted = new ArrayList<>();
        AtomicBoolean invoked = new AtomicBoolean(false);
        fx(() -> {
            AgentConsoleView view = newView(submitted);
            view.setArgumentSuggestions(text -> {
                invoked.set(true);
                if (text.startsWith("/resume")) return List.of(
                        new AgentConsoleView.ArgumentSuggestion("任务 2 · 部署 nps", "/resume 2"));
                return List.of();
            });
            TextArea input = (TextArea) field(view, "input");
            input.setText("/resume ");
            ListView<String> suggestions = (ListView<String>) field(view, "suggestions");
            assertTrue(suggestions.isVisible(), "命令加空格后应出现参数候选");
            assertEquals(1, suggestions.getItems().size(), "候选数应与任务列表一致");
            assertTrue(suggestions.getItems().get(0).contains("部署 nps"));
        });
        assertTrue(invoked.get(), "输入带空格的命令应触发参数提供器");
    }

    @Test
    void acceptingArgumentCandidateFillsExecutableCommand() throws Exception {
        List<String> submitted = new ArrayList<>();
        fx(() -> {
            AgentConsoleView view = newView(submitted);
            view.setArgumentSuggestions(text -> List.of(
                    new AgentConsoleView.ArgumentSuggestion("任务 2 · 部署 nps", "/resume 2")));
            TextArea input = (TextArea) field(view, "input");
            input.setText("/resume 2");
            invoke(view, "acceptSuggestion");
            assertEquals("/resume 2", input.getText(), "选中候选应填入可直接执行的完整命令");
            assertTrue(submitted.isEmpty(), "填入后不应立即提交，由用户回车执行");
        });
    }

    @Test
    void recoverableErrorCardOffersRetryAndModelSwitch() throws Exception {
        List<String> submitted = new ArrayList<>();
        AtomicBoolean retried = new AtomicBoolean(false);
        fx(() -> {
            AgentConsoleView view = newView(submitted);
            view.printRecoverableError("AI 输出被截断", () -> retried.set(true), () -> { });
            VBox transcript = (VBox) field(view, "transcript");
            List<Button> buttons = new ArrayList<>();
            collectButtons(transcript, buttons);
            Button retry = buttons.stream().filter(button -> button.getText().contains("重试")).findFirst().orElse(null);
            assertNotNull(retry, "错误卡片必须提供“重试上一步”");
            retry.fire();
            assertTrue(retried.get(), "重试按钮必须触发重试回调");
        });
    }

    @Test
    void historyDialogFiltersByTitleAndContent() throws Exception {
        List<AgentHistoryStore.EventRecord> events = List.of(
                new AgentHistoryStore.EventRecord("s", "USER", "部署 nps", 1000L, List.of(), null),
                new AgentHistoryStore.EventRecord("s", "RESPONSE", "已生成安装命令", 2000L, List.of(), null));
        AgentHistoryStore.TaskRecord matching = new AgentHistoryStore.TaskRecord(
                "root@host:22", "t-1", "部署任务", 1, 3000L, events);
        AgentHistoryStore.TaskRecord other = new AgentHistoryStore.TaskRecord(
                "root@host:22", "t-2", "检查磁盘", 2, 4000L, List.of(
                new AgentHistoryStore.EventRecord("s", "RESPONSE", "磁盘使用率 78%", 4000L, List.of(), null)));
        fx(() -> {
            I18n.setLocale(java.util.Locale.SIMPLIFIED_CHINESE, false);
            AgentHistoryDialog dialog = new AgentHistoryDialog(null,
                    List.of(AgentHistoryDialog.from(matching, "root@host:22", true),
                            AgentHistoryDialog.from(other, "root@host:22", false)),
                    noOpActions());
            assertEquals(2, dialog.visibleItems().size(), "初始应显示全部任务");
            dialog.applyFilter("部署");
            List<AgentHistoryDialog.HistoryItem> filtered = dialog.visibleItems();
            assertEquals(1, filtered.size(), "搜索应同时匹配标题与消息内容");
            assertEquals("t-1", filtered.get(0).taskId());
            dialog.applyFilter("");
            assertEquals(2, dialog.visibleItems().size(), "清空搜索应恢复全部任务");
        });
    }

    private static AgentConsoleView newView(List<String> submitted) {
        return new AgentConsoleView(List.of(
                new AgentConsoleView.Command("/help", "help"),
                new AgentConsoleView.Command("/resume", "resume"),
                new AgentConsoleView.Command("/model", "model"),
                new AgentConsoleView.Command("/auto", "auto")),
                submitted::add, () -> {}, () -> {});
    }

    private static AgentHistoryDialog.HistoryActions noOpActions() {
        return new AgentHistoryDialog.HistoryActions() {
            @Override public void resume(String taskId) { }
            @Override public void rename(String taskId, String title) { }
            @Override public void delete(String taskId) { }
            @Override public void clearAll() { }
            @Override public void export(java.nio.file.Path file) { }
        };
    }

    private static void collectButtons(Node node, List<Button> buttons) {
        if (node instanceof Button button) buttons.add(button);
        if (node instanceof javafx.scene.layout.Pane pane) {
            ObservableList<Node> children = pane.getChildrenUnmodifiable();
            for (Node child : children) collectButtons(child, buttons);
        }
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
            try { action.run(); }
            catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
