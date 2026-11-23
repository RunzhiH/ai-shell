package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审批卡片「拒绝」按钮位置回归（GitHub #5）：拒绝必须与执行分属不同行、
 * 保持足够竖直间距并使用红色醒目样式，防止用户点执行时误中拒绝。
 */
class ApprovalCardRejectPlacementTest {

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
    void rejectSitsOnItsOwnRowFarFromExecute() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> { }, () -> { }, () -> { });
            view.printCommandProposal("rm -rf /tmp/cache", "中风险", ok -> { }, cmd -> { });
            viewRef.set(view);
            Stage window = new Stage();
            window.setScene(new javafx.scene.Scene(view, 352, 400));
            window.show();
            stage = window;
        });
        pump();
        AgentConsoleView view = viewRef.get();

        VBox transcript = (VBox) field(view, "transcript");
        Node card = transcript.getChildren().stream()
                .filter(node -> node.getStyleClass().contains("ai-command-card"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("审批卡片未渲染"));
        VBox actions = childrenOf(card).stream()
                .filter(node -> node instanceof VBox box && box.getStyleClass().isEmpty()
                        && box.getChildrenUnmodifiable().size() == 2)
                .map(node -> (VBox) node)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到审批卡片的操作区"));

        assertEquals(2, actions.getChildren().size(), "操作区必须分成主操作行与拒绝行");
        assertEquals(10.0, actions.getSpacing(), 0.001, "拒绝行与执行行应拉开 10px 竖直间距");

        Node primaryRow = actions.getChildren().get(0);
        List<Button> primaryButtons = buttonsOf(primaryRow);
        assertTrue(primaryButtons.stream().anyMatch(button -> "执行".equals(button.getText())), "主操作行应包含执行");
        assertTrue(primaryButtons.stream().noneMatch(button -> "拒绝".equals(button.getText())),
                "拒绝不得与执行同处一行，避免误点");

        Node rejectRow = actions.getChildren().get(1);
        List<Button> rejectButtons = buttonsOf(rejectRow);
        Button reject = rejectButtons.stream()
                .filter(button -> "拒绝".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("拒绝行未找到拒绝按钮"));
        assertTrue(reject.getStyleClass().contains("agent-reject-button"), "拒绝应使用红色醒目样式");
        assertNotNull(reject.getTooltip(), "拒绝按钮应有说明 tooltip");
    }

    private List<Node> childrenOf(Node node) {
        return node instanceof Parent parent ? List.copyOf(parent.getChildrenUnmodifiable()) : List.of();
    }

    private List<Button> buttonsOf(Node container) {
        return childrenOf(container).stream()
                .filter(node -> node instanceof Button)
                .map(node -> (Button) node)
                .toList();
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

    private Object field(Object target, String name) throws Exception {
        var declared = target.getClass().getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(target);
    }
}
