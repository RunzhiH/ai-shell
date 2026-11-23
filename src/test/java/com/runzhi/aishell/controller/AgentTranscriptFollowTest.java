package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新对话到达时转录区必须停在最底部。
 * 修复点：setVvalue(1.0) 必须在新内容布局完成之后执行，否则像素锚定使定位失效。
 */
class AgentTranscriptFollowTest {
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @AfterEach
    void hideStage() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            if (stage != null) { stage.hide(); stage = null; }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void streamingMessagesKeepTheViewAtTheBottom() throws Exception {
        var refs = showView();
        for (int i = 0; i < 10; i++) {
            final int index = i;
            fx(() -> refs.view().printAssistant("第 " + index + " 条 " + "内容".repeat(30)));
            pump();
        }
        assertAtBottom(refs);
    }

    @Test
    void replayingAConversationEndsAtTheBottom() throws Exception {
        var refs = showView();
        fx(() -> {
            AgentConsoleView view = refs.view();
            view.clearMessages();
            for (int i = 0; i < 10; i++) view.printAssistant("重放 " + i + " " + "内容".repeat(30));
        });
        pump();
        assertAtBottom(refs);
    }

    @Test
    void userScrolledUpIsNotPulledDownByNewMessages() throws Exception {
        var refs = showView();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            fx(() -> refs.view().printAssistant("第 " + index + " 条 " + "内容".repeat(30)));
            pump();
        }
        fx(() -> refs.scroll().setVvalue(0.0));
        pump();
        fx(() -> refs.view().printAssistant("用户在顶部时到达的消息"));
        pump();
        assertEquals(0.0, refs.scroll().getVvalue(), 0.001,
                "a user reading the top must not be pulled down by new messages");
    }

    private ViewRefs showView() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        AtomicReference<ScrollPane> scrollRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            Scene scene = new Scene(view, 352, 400);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
            Stage window = new Stage();
            window.setScene(scene);
            window.show();
            viewRef.set(view);
            scrollRef.set((ScrollPane) field(view, "scroll"));
            stageRef.set(window);
            this.stage = window;
        });
        pump();
        return new ViewRefs(viewRef.get(), scrollRef.get());
    }

    private void assertAtBottom(ViewRefs refs) throws Exception {
        // 环境负载下最后一次跟随可能需要多个脉冲才落底：带截止期轮询，
        // 超时仍不在底部才判失败（失败信息附带实际 vvalue 便于诊断）。
        long deadline = System.currentTimeMillis() + 10000;
        double vvalue = -1;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Double> snapshot = new AtomicReference<>();
            CountDownLatch read = new CountDownLatch(1);
            Platform.runLater(() -> { snapshot.set(refs.scroll().getVvalue()); read.countDown(); });
            assertTrue(read.await(5, TimeUnit.SECONDS));
            vvalue = snapshot.get();
            if (vvalue > 0.999) break;
            Thread.sleep(80);
        }
        assertEquals(1.0, vvalue, 0.001, "new dialog must leave the view at the very bottom");
        fx(() -> {
            VBox transcript = (VBox) field(refs.view(), "transcript");
            ScrollPane scroll = refs.scroll();
            double viewport = scroll.getViewportBounds().getHeight();
            double content = transcript.getLayoutBounds().getHeight();
            assertTrue(content > viewport, "transcript must be scrollable for this assertion");
            double offset = scroll.getVvalue() * (content - viewport);
            Node last = transcript.getChildren().get(transcript.getChildren().size() - 1);
            assertTrue(last.getBoundsInParent().getMaxY() <= offset + viewport + 1,
                    "last message must be visible at the bottom");
        });
    }

    /** 等待布局脉冲：真实 Stage 上挂载的 Scene 才会进行布局。 */
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
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private record ViewRefs(AgentConsoleView view, ScrollPane scroll) { }

    private static Object field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
