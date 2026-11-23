package com.runzhi.aishell.terminal;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端输入恢复回归（GitHub #7）：Agent 执行 docker 等命令后切回终端应能继续键入——
 * 残留审批面板被移除、命令输入框恢复可用并聚焦；超长 Agent 输出镜像被截断。
 */
class TerminalInputRestoreTest {

    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException started) {
            ready.countDown();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @AfterEach
    void hideStage() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
                stage = null;
            }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void restoreRemovesStaleConfirmationAndFocusesTheInput() throws Exception {
        Tab tab = new Tab();
        AtomicReference<TextField> inputRef = new AtomicReference<>();
        AtomicReference<VBox> confirmationRef = new AtomicReference<>();
        fx(() -> {
            TextField input = new TextField();
            input.setId("commandInput");
            input.setDisable(true); // 模拟执行期间被禁用的残留状态
            VBox confirmation = new VBox();
            confirmation.setId("aiCommandConfirmation");
            VBox content = new VBox(confirmation, input);
            tab.setContent(content);
            Scene scene = new Scene(content, 352, 200);
            stage = new Stage();
            stage.setScene(scene);
            stage.show();
            inputRef.set(input);
            confirmationRef.set(confirmation);
        });
        pump();

        fx(() -> InteractiveTerminalView.restoreTerminalInput(tab));
        pump();

        TextField input = inputRef.get();
        VBox confirmation = confirmationRef.get();
        assertFalse(input.isDisabled(), "命令输入框应恢复可编辑");
        assertNull(confirmation.getParent(), "残留 AI 确认面板应被移除");
        assertTrue(input.isFocused(), "焦点应回到命令输入框");
    }

    @Test
    void restoreIsSafeForTabsWithoutConfirmationOrInput() throws Exception {
        Tab tab = new Tab();
        fx(() -> tab.setContent(new VBox()));
        fx(() -> InteractiveTerminalView.restoreTerminalInput(tab)); // 不得抛异常
    }

    @Test
    void longAgentOutputIsTruncatedForTheTerminalMirror() {
        String shortOutput = InteractiveTerminalView.truncateMirror("docker ps");
        assertEquals("docker ps", shortOutput);

        String huge = "line of docker logs\n".repeat(1000); // 约 21KB
        String truncated = InteractiveTerminalView.truncateMirror(huge);
        assertTrue(truncated.length() < huge.length(), "超长输出必须截断");
        assertTrue(truncated.contains("已截断"), "应给出截断提示");
        assertTrue(truncated.startsWith("line of docker logs"), "应保留开头内容");
        // CRLF 归一化后同样截断
        String crlf = "docker build output\r\n".repeat(1000);
        assertTrue(InteractiveTerminalView.truncateMirror(crlf).contains("已截断"));
    }

    private void pump() throws Exception {
        for (int i = 0; i < 3; i++) {
            CountDownLatch done = new CountDownLatch(1);
            Platform.runLater(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            Thread.sleep(60);
        }
    }

    private void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
