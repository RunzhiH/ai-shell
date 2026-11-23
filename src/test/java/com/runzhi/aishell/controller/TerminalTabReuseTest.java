package com.runzhi.aishell.controller;

import com.runzhi.aishell.ssh.domain.ConnectionParams;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端标签页管理（GitHub issue #2）：同一连接复用已有标签页，不再重复创建。
 * 不加载终端 FXML：用空内容标签 + 空视图桩，聚焦“复用还是新建”的判定逻辑。
 */
class TerminalTabReuseTest {

    private ReuseFixture controller;
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
        controller = new ReuseFixture();
        fieldOf("terminalTabPane").set(controller, pane);
    }

    /** 终端视图桩：新建标签时不加载 terminal-tab.fxml，保持测试在 headless 下稳定。 */
    private static class ReuseFixture extends TerminalPanelController {
        @Override
        public Parent createTerminalAndFileManagerViewForTab(String tabId) {
            return new VBox();
        }
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

    private ConnectionParams params(String host, String user) {
        ConnectionParams p = new ConnectionParams();
        p.setHost(host);
        p.setPort(22);
        p.setUsername(user);
        return p;
    }

    @Test
    void connectingToAlreadyOpenConnectionReusesItsTab() {
        Tab existing = controller.createConfiguredTerminalTab(params("10.0.0.30", "root"));
        runOnFx(() -> pane.getTabs().add(existing));

        runOnFx(() -> controller.createNewTerminalTabWithParams(params("10.0.0.30", "root")));

        assertEquals(1, pane.getTabs().size(), "同一连接不得重复创建标签");
        assertSame(existing, pane.getSelectionModel().getSelectedItem());
    }

    @Test
    void differentConnectionStillOpensItsOwnTab() {
        Tab existing = controller.createConfiguredTerminalTab(params("10.0.0.30", "root"));
        runOnFx(() -> pane.getTabs().add(existing));

        runOnFx(() -> controller.createNewTerminalTabWithParams(params("10.0.0.31", "root")));

        assertEquals(2, pane.getTabs().size(), "不同连接仍应新建标签");
        assertNotSame(existing, pane.getSelectionModel().getSelectedItem());
    }

    @Test
    void reuseMatchesUserHostPortRegardlessOfProfileName() {
        // 相同 user@host:port、不同显示名仍视为同一连接
        ConnectionParams stored = params("10.0.0.30", "root");
        stored.setProfileName("生产-01");
        Tab existing = controller.createConfiguredTerminalTab(stored);
        runOnFx(() -> pane.getTabs().add(existing));

        ConnectionParams again = params("10.0.0.30", "root");
        again.setProfileName("别名");
        runOnFx(() -> controller.createNewTerminalTabWithParams(again));

        assertEquals(1, pane.getTabs().size(), "别名连同一台机器也应复用已有标签");
        assertSame(existing, pane.getSelectionModel().getSelectedItem());
    }
}
