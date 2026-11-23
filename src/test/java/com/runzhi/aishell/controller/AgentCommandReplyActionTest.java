package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 面板优化回归（GitHub #11）：指令类回复附「粘贴到终端执行」按钮；
 * 说明性文字不附按钮；点击按钮把命令交给会话终端。
 */
class AgentCommandReplyActionTest {

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
    void commandLookingRepliesOfferARunInTerminalButton() throws Exception {
        List<String> executed = new ArrayList<>();
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> { }, () -> { }, () -> { });
            view.setCommandRunner(executed::add);
            view.printAssistant("ls -la /var/log");
            viewRef.set(view);
            Scene scene = new Scene(view, 352, 400);
            stage = new Stage();
            stage.setScene(scene);
            stage.show();
        });
        pump();

        Button run = findButton(viewRef.get(), "粘贴到终端执行");
        assertNotNull(run, "指令类回复应附「粘贴到终端执行」按钮");

        fx(() -> run.fire());
        pump();
        assertEquals(List.of("ls -la /var/log"), executed, "点击按钮应把命令交给终端执行");
    }

    @Test
    void proseRepliesDoNotOfferTheButton() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> { }, () -> { }, () -> { });
            view.printAssistant("建议先检查磁盘占用，再决定是否清理。");
            view.printAssistant("The target server runs linux.");
            viewRef.set(view);
        });
        pump();

        assertNull(findButton(viewRef.get(), "粘贴到终端执行"), "说明性文字不应附执行按钮");
    }

    @Test
    void looksLikeCommandHandlesEdgeCases() {
        assertTrue(AgentConsoleView.looksLikeCommand("sudo systemctl restart nginx"));
        assertTrue(AgentConsoleView.looksLikeCommand("df -h"));
        assertFalse(AgentConsoleView.looksLikeCommand(""));
        assertFalse(AgentConsoleView.looksLikeCommand(null));
        assertFalse(AgentConsoleView.looksLikeCommand("第一行\n第二行"));
        assertFalse(AgentConsoleView.looksLikeCommand("这是说明文字。"));
        assertFalse(AgentConsoleView.looksLikeCommand("done."));
    }

    private Button findButton(AgentConsoleView view, String text) throws Exception {
        var field = AgentConsoleView.class.getDeclaredField("transcript");
        field.setAccessible(true);
        VBox transcript = (VBox) field.get(view);
        return buttonsOf(transcript).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElse(null);
    }

    private List<Button> buttonsOf(Parent parent) {
        List<Button> found = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Button button) {
                found.add(button);
            } else if (node instanceof Parent child) {
                found.addAll(buttonsOf(child));
            }
        }
        return found;
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
