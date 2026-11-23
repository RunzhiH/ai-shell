package com.runzhi.aishell.controller;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 历史跟随标签页回归（GitHub #10）：关闭终端标签时把最近对话同步落盘，
 * 避免重连后历史内容缺失。
 */
class AgentHistoryTabCloseTest {

    private TerminalPanelController controller;
    private PersistSpy workspace;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @BeforeEach
    void setUp() throws Exception {
        controller = new TerminalPanelController();
        workspace = new PersistSpy();
        fieldOf("terminalTabPane").set(controller, new javafx.scene.control.TabPane());
        fieldOf("agentWorkspaceController").set(controller, workspace);
    }

    private Field fieldOf(String name) throws Exception {
        Field field = TerminalPanelController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void closingATabPersistsTheAgentHistory() {
        Tab tab = controller.createConfiguredTerminalTab(null);
        assertNotNull(tab.getOnClosed(), "已配置标签应注册关闭处理器");

        tab.getOnClosed().handle(new Event(Tab.CLOSED_EVENT));

        assertEquals(1, workspace.persistCalls, "关闭标签时应同步保存 Agent 历史");
    }

    /** 裸控制器没有 Agent 工作区时也不得抛异常。 */
    @Test
    void closingATabWithoutWorkspaceIsSafe() throws Exception {
        fieldOf("agentWorkspaceController").set(controller, null);
        Tab tab = controller.createConfiguredTerminalTab(null);
        tab.getOnClosed().handle(new Event(Tab.CLOSED_EVENT));
    }

    private static class PersistSpy extends AgentWorkspaceController {
        int persistCalls;

        @Override
        public void persistHistoryOnTabClosed() {
            persistCalls++;
        }
    }
}
