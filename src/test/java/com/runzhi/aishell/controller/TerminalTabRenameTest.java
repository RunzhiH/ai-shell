package com.runzhi.aishell.controller;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端标签页管理回归（GitHub #8）：标签名称自定义（右键重命名）。
 */
class TerminalTabRenameTest {

    private TerminalPanelController controller;
    private TabPane pane;

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
        pane = new TabPane();
        controller = new TerminalPanelController();
        fieldOf("terminalTabPane").set(controller, pane);
    }

    private Field fieldOf(String name) throws Exception {
        Field field = TerminalPanelController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void runOnFx(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "runOnFx 执行超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("runOnFx 被中断");
        }
        if (failure.get() != null) throw new AssertionError("FX 线程内断言/异常失败", failure.get());
    }

    @Test
    void configuredTabsOfferARenameContextMenu() {
        Tab tab = controller.createConfiguredTerminalTab(null);
        ContextMenu menu = tab.getContextMenu();
        assertNotNull(menu, "终端标签应带右键菜单");
        assertEquals(2, menu.getItems().size());
        assertEquals("重命名标签", menu.getItems().get(0).getText());
        assertEquals("关闭标签", menu.getItems().get(1).getText());
    }

    @Test
    void applyRenameUpdatesTitleAndBaseTitle() {
        Tab tab = controller.createConfiguredTerminalTab(null);
        String original = tab.getText();

        runOnFx(() -> controller.applyTabRename(tab, "我的服务器"));

        assertEquals("我的服务器", tab.getText());
        assertEquals("我的服务器", tab.getProperties().get(TerminalPanelController.BASE_TITLE_KEY));
        assertNotEquals(original, "我的服务器");
    }

    @Test
    void renameIsRejectedForBlankNames() {
        Tab tab = controller.createConfiguredTerminalTab(null);
        String original = tab.getText();
        runOnFx(() -> controller.applyTabRename(tab, "   "));
        assertEquals(original, tab.getText(), "空白名称不得覆盖现有标题");
    }
}
