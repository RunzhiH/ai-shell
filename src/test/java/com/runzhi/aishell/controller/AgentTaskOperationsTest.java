/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentHistoryStore;
import com.runzhi.aishell.agent.AgentHistoryWriter;
import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** /rename、/delete、/clear-history 与更多菜单的任务操作验证。 */
class AgentTaskOperationsTest {
    private static Locale originalLocale;
    private AgentWorkspaceController controller;
    private final AtomicReference<Tab> active = new AtomicReference<>();
    private Tab a;

    @BeforeAll
    static void startFx() throws Exception {
        originalLocale = I18n.current();
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @AfterEach
    void dispose() throws Exception {
        if (controller != null) fx(() -> controller.onWindowClosing());
        if (a != null) AgentSessionTracker.getInstance().clear(a.getId());
        if (originalLocale != null) I18n.setLocale(originalLocale, false);
    }

    @Test
    void renameUpdatesTaskTitleAndPersists() throws Exception {
        loadWorkspace();
        a = sessionTab("ops-01 · 1", "agent-rename-a", "10.0.0.40");
        fx(() -> { active.set(a); controller.configure(active::get, null, AgentWorkspaceBridge.empty()); });
        fx(() -> invoke("/rename 部署 nps"));
        Label taskLabel = (Label) console().lookup(".agent-task-label");
        assertTrue(taskLabel.getText().contains("部署 nps"), "任务名称应同步更新: " + taskLabel.getText());

        List<AgentHistoryStore.TaskRecord> loaded = persistedFor(a);
        assertEquals(1, loaded.size());
        assertEquals("部署 nps", loaded.get(0).title(), "重命名应写回历史");
    }

    @Test
    void deleteCurrentTaskFallsBackToMostRecentTask() throws Exception {
        loadWorkspace();
        a = sessionTab("ops-02 · 1", "agent-delete-a", "10.0.0.41");
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
            invoke("/new 备份任务");
            invoke("/new 部署任务");
        });
        autoConfirm(); // /delete 弹出的确认框由后台线程替为“确定”
        fx(() -> invoke("/delete"));
        fx(() -> { });
        Label taskLabel = (Label) console().lookup(".agent-task-label");
        assertTrue(taskLabel.getText().contains("备份任务"), "删除当前任务后应恢复到最近任务: " + taskLabel.getText());

        List<AgentHistoryStore.TaskRecord> loaded = persistedFor(a);
        assertEquals(2, loaded.size(), "默认任务与备份任务应保留在历史中");
        assertTrue(loaded.stream().anyMatch(task -> "备份任务".equals(task.title())), "最近任务应保留在历史中");
    }

    @Test
    void clearHistoryRemovesAllTasksForConnection() throws Exception {
        loadWorkspace();
        a = sessionTab("ops-03 · 1", "agent-clear-a", "10.0.0.42");
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
            invoke("/new 临时任务");
        });
        autoConfirm();
        fx(() -> invoke("/clear-history"));
        fx(() -> { });
        List<AgentHistoryStore.TaskRecord> loaded = persistedFor(a);
        assertTrue(loaded.stream().noneMatch(task -> "临时任务".equals(task.title())),
                "清空历史应删除当前连接的全部任务");
    }

    @Test
    void moreMenuExposesTaskOperations() throws Exception {
        loadWorkspace();
        a = sessionTab("ops-04 · 1", "agent-menu-a", "10.0.0.43");
        fx(() -> { active.set(a); controller.configure(active::get, null, AgentWorkspaceBridge.empty()); });
        javafx.scene.control.MenuButton more =
                (javafx.scene.control.MenuButton) console().lookup(".agent-more-menu");
        assertNotNull(more, "目标行必须提供“更多”菜单");
        assertEquals(6, more.getItems().size(), "新建/历史/权限/自动批准/清空/删除六项");
        List<String> labels = more.getItems().stream()
                .map(item -> item.getText() == null ? "" : item.getText()).toList();
        assertTrue(labels.stream().anyMatch(text -> text.contains("新建")), "缺少新建任务: " + labels);
        assertTrue(labels.stream().anyMatch(text -> text.contains("历史")), "缺少会话历史: " + labels);
        assertTrue(labels.stream().anyMatch(text -> text.contains("删除")), "缺少删除任务: " + labels);
    }

    @Test
    @SuppressWarnings("unchecked")
    void argumentCompletionListsTasksModelsAndSwitches() throws Exception {
        loadWorkspace();
        a = sessionTab("ops-05 · 1", "agent-args-a", "10.0.0.44");
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
            invoke("/new 部署 nps");
        });
        Function<String, List<AgentConsoleView.ArgumentSuggestion>> provider =
                (Function<String, List<AgentConsoleView.ArgumentSuggestion>>) field(console(), "argumentSuggestionProvider");

        List<AgentConsoleView.ArgumentSuggestion> resumeItems = provider.apply("/resume ");
        assertFalse(resumeItems.isEmpty(), "/resume 应列出当前连接任务");
        assertTrue(resumeItems.stream().allMatch(item -> item.value().startsWith("/resume")),
                "参数候选应填入可直接执行的完整命令");

        List<AgentConsoleView.ArgumentSuggestion> autoItems = provider.apply("/auto ");
        assertEquals(2, autoItems.size(), "/auto 应列出 on/off");

        List<AgentConsoleView.ArgumentSuggestion> models = provider.apply("/model ");
        assertFalse(models.isEmpty(), "/model 应列出已配置模型");
        assertTrue(models.stream().allMatch(item -> item.value().startsWith("/model")),
                "模型候选应可回车执行");

        List<AgentConsoleView.ArgumentSuggestion> filtered = provider.apply("/resume 部署");
        assertFalse(filtered.stream().noneMatch(item -> item.display().contains("部署")),
                "输入任务关键字应能过滤出对应任务");
    }

    private void loadWorkspace() throws Exception {
        fx(() -> {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE, false);
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/agent-workspace.fxml"));
            loader.setResources(I18n.bundle());
            loader.load();
            controller = loader.getController();
        });
        assertNotNull(controller);
    }

    /**
     * 删除/清空命令会调用 showAndWait 弹出确认框；测试启一个后台线程在对话框出现后替用户按“确定”。
     */
    private void autoConfirm() {
        Thread waiter = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && stage.isShowing()
                            && stage.getScene() != null
                            && stage.getScene().getRoot() instanceof DialogPane pane) {
                        Node ok = pane.lookupButton(javafx.scene.control.ButtonType.OK);
                        if (ok instanceof Button button) { Platform.runLater(button::fire); return; }
                    }
                }
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "test-confirm-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void invoke(String line) throws Exception {
        Method input = AgentWorkspaceController.class.getDeclaredMethod("handleCommand", String.class);
        input.setAccessible(true);
        input.invoke(controller, line);
    }

    private List<AgentHistoryStore.TaskRecord> persistedFor(Tab tab) throws Exception {
        // persistHistory 是防抖异步写盘，测试读取前强制 flush，避免时序竞争
        AgentHistoryWriter writer = (AgentHistoryWriter) field(controller, "historyWriter");
        writer.flush();
        AgentHistoryStore store = (AgentHistoryStore) field(controller, "historyStore");
        String connection = connectionKeyOf(tab);
        return store.load().stream().filter(task -> task.connectionKey().equals(connection)).toList();
    }

    private String connectionKeyOf(Tab tab) {
        Object stored = tab.getProperties().get(TerminalPanelController.CONNECTION_PARAMS_KEY);
        if (stored instanceof ConnectionParams params) {
            return (params.getUsername() == null ? "" : params.getUsername()) + "@"
                    + params.getHost() + ":" + params.getPort();
        }
        return tab.getId();
    }

    private AgentConsoleView console() throws Exception {
        return (AgentConsoleView) field(controller, "console");
    }

    /**
     * 主机名每次运行唯一：默认历史文件（target/test-home）跨运行累积，
     * 同主机的旧任务会被 loadPersistedHistory 重新加载，干扰断言。
     */
    private static Tab sessionTab(String title, String id, String ignoredHostHint) {
        Tab tab = new Tab(title);
        tab.setId(id);
        ConnectionParams params = new ConnectionParams();
        params.setHost("agent-test-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10) + ".example");
        params.setPort(22);
        params.setUsername("root");
        tab.getProperties().put(TerminalPanelController.CONNECTION_PARAMS_KEY, params);
        return tab;
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
