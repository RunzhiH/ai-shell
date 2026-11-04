/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.AIConfigManager;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
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

import static org.junit.jupiter.api.Assertions.*;

/** Switching terminal tabs must switch the Agent transcript, including same-host sessions. */
class AgentWorkspaceTabSwitchTest {
    private static Locale originalLocale;
    private AgentWorkspaceController controller;
    private final AtomicReference<Tab> active = new AtomicReference<>();
    private Tab a;
    private Tab b;

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
        if (b != null) AgentSessionTracker.getInstance().clear(b.getId());
        if (originalLocale != null) I18n.setLocale(originalLocale, false);
    }

    @Test
    void switchingTerminalsSwapsEachTabsConversation() throws Exception {
        loadWorkspace();
        a = sessionTab("staging-01 · 1", "agent-switch-a", "10.0.0.30");
        b = sessionTab("staging-01 · 2", "agent-switch-b", "10.0.0.30");
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
            // The second tab is opened once before its events arrive, like a real selection.
            active.set(b);
            controller.showTab(b);
            active.set(a);
            controller.showTab(a);
        });

        fx(() -> {
            AgentActivityBus.publish(a.getId(), AgentActivityBus.Kind.USER, "A-only-message");
            AgentActivityBus.publish(a.getId(), AgentActivityBus.Kind.RESPONSE, "A-only-reply");
        });
        fx(() -> {
            AgentActivityBus.publish(b.getId(), AgentActivityBus.Kind.USER, "B-only-message");
            AgentActivityBus.publish(b.getId(), AgentActivityBus.Kind.RESPONSE, "B-only-reply");
        });
        fx(() -> { });

        assertNotEquals(a.getProperties().get("agentTaskId"), b.getProperties().get("agentTaskId"),
                "同一主机的两个终端标签不能共用默认任务");

        fx(() -> { active.set(a); controller.showTab(a); });
        String aText = transcriptText();
        assertTrue(aText.contains("A-only-message"), aText);
        assertTrue(aText.contains("A-only-reply"), aText);
        assertFalse(aText.contains("B-only-message"), "A 标签不应显示 B 的会话记录");

        fx(() -> { active.set(b); controller.showTab(b); });
        String bText = transcriptText();
        assertTrue(bText.contains("B-only-message"), bText);
        assertTrue(bText.contains("B-only-reply"), bText);
        assertFalse(bText.contains("A-only-message"), "切到 B 后不应继续显示 A 的会话记录");
    }

    @Test
    void eventsArrivingBeforeFirstDisplayAreKeptForThatTerminal() throws Exception {
        loadWorkspace();
        a = sessionTab("prod-web-01 · 1", "agent-early-a", "10.0.0.31");
        b = sessionTab("prod-web-01 · 2", "agent-early-b", "10.0.0.31");
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
            AgentActivityBus.publish(b.getId(), AgentActivityBus.Kind.RESPONSE, "early-B-content");
        });
        fx(() -> { });

        fx(() -> { active.set(b); controller.showTab(b); });
        String bText = transcriptText();
        assertTrue(bText.contains("early-B-content"), "首次显示前到达的记录不能丢失: " + bText);
    }

    @Test
    void questionArrivingOnHiddenTabStaysAnswerableAfterSwitch() throws Exception {
        loadWorkspace();
        a = sessionTab("db-01 · 1", "agent-question-a", "10.0.0.32");
        b = sessionTab("db-01 · 2", "agent-question-b", "10.0.0.32");
        AtomicReference<String> answered = new AtomicReference<>();
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
            AgentActivityBus.publishAsk(b.getId(), "请选择处理方式", List.of("只读检查", "重启服务"), answered::set);
        });
        fx(() -> { });

        fx(() -> {
            active.set(b);
            controller.showTab(b);
            Method input = AgentWorkspaceController.class.getDeclaredMethod("handleLine", String.class);
            input.setAccessible(true);
            input.invoke(controller, "2");
        });
        assertEquals("重启服务", answered.get(), "切到该标签后仍应能回答它自己的提问");
    }
    @Test
    @SuppressWarnings("unchecked")
    void aiSelectorSwitchesModelAndStatusShowsProfile() throws Exception {
        loadWorkspace();
        a = sessionTab("ai-switch · 1", "agent-ai-switch-a", "10.0.0.33");
        fx(() -> {
            active.set(a);
            controller.configure(active::get, null, AgentWorkspaceBridge.empty());
        });

        AgentConsoleView console = (AgentConsoleView) field(controller, "console");
        Label taskLabel = (Label) console.lookup(".agent-task-label");
        assertNotNull(taskLabel, "目标下方必须显示当前任务名称");
        assertTrue(taskLabel.isVisible());
        assertTrue(taskLabel.getText().contains("任务"), taskLabel.getText());
        ComboBox<AgentConsoleView.AiProfile> selector =
                (ComboBox<AgentConsoleView.AiProfile>) console.lookup(".agent-ai-selector");
        assertNotNull(selector, "Agent 工作区必须提供 AI 模型切换入口");

        AIConfigManager manager = AIConfigManager.getInstance();
        String original = manager.getCurrentConfig() == null ? null : manager.getCurrentConfig().getName();
        AgentConsoleView.AiProfile targetProfile = selector.getItems().stream()
                .filter(profile -> !profile.id().equals(original)).findFirst().orElse(null);
        assertNotNull(targetProfile, "测试环境至少需要两个 AI 配置");
        try {
            fx(() -> selector.setValue(targetProfile));
            assertEquals(targetProfile.id(), manager.getCurrentConfig().getName(),
                    "选择器必须真正切换当前 AI 配置");
            assertEquals(targetProfile.label(), selector.getValue().label(),
                    "下拉框显示模型而不是供应商");

            Label status = (Label) console.lookup(".agent-status-label");
            assertNotNull(status);
            assertEquals(manager.getCurrentConfig().getName(), status.getText(),
                    "状态行保留配置/供应商名作为辅助信息");
            assertFalse(status.getText().contains("任务"));
            assertFalse(status.getText().toLowerCase(Locale.ROOT).contains("task"));
        } finally {
            if (original != null) fx(() -> manager.setDefaultConfig(original));
        }
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

    private static Tab sessionTab(String title, String id, String host) {
        Tab tab = new Tab(title);
        tab.setId(id);
        ConnectionParams params = new ConnectionParams();
        params.setHost(host);
        params.setPort(22);
        params.setUsername("root");
        params.setProfileName(title);
        tab.getProperties().put(TerminalPanelController.CONNECTION_PARAMS_KEY, params);
        tab.getProperties().put(TerminalPanelController.BASE_TITLE_KEY, title);
        return tab;
    }

    private String transcriptText() throws Exception {
        AgentConsoleView console = (AgentConsoleView) field(controller, "console");
        VBox transcript = (VBox) field(console, "transcript");
        StringBuilder text = new StringBuilder();
        collect(transcript, text);
        return text.toString();
    }

    private static void collect(Node node, StringBuilder text) {
        if (node instanceof Text value) text.append(value.getText()).append('\n');
        else if (node instanceof Label label) text.append(label.getText()).append('\n');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, text);
        }
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
