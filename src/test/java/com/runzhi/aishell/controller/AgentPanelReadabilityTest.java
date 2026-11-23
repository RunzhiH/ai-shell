package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 面板可读性回归（GitHub #4）：消息行间距、角色配色与「滚到最下方」。
 * 与既有转录测试同一套真实 Stage 挂载方式（headless 下需要窗口驱动布局）。
 */
class AgentPanelReadabilityTest {

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
    void messageAndLineSpacingAreRoomy() throws Exception {
        var refs = showView();
        fx(() -> refs.view().printAssistant("第一段内容"));
        pump();
        VBox transcript = (VBox) field(refs.view(), "transcript");
        assertEquals(12.0, transcript.getSpacing(), 0.001, "消息卡之间应留 12px 行间距");
        List<TextFlow> flows = flowsOf(transcript);
        assertFalse(flows.isEmpty(), "应能找到消息正文 TextFlow");
        for (TextFlow flow : flows) {
            assertEquals(5.0, flow.getLineSpacing(), 0.001, "正文行内行间距应为 5px");
        }
    }

    @Test
    void differentRolesGetDifferentStyleClasses() throws Exception {
        var refs = showView();
        fx(() -> {
            refs.view().printUser("用户输入");
            refs.view().printAssistant("AI 回复");
            refs.view().printTechnical(AgentConsoleView.Role.THINK, "思考过程");
            refs.view().printTechnical(AgentConsoleView.Role.TOOL, "工具输出");
        });
        pump();
        List<String> classes = textClassesOf(refs.view());
        assertTrue(classes.contains("agent-message-text-user"), "用户输入应带 user 配色类");
        assertTrue(classes.contains("agent-message-text-assistant"), "AI 回复应带 assistant 配色类");
        assertTrue(classes.contains("agent-message-text-think"), "思考应带 think 配色类");
        assertTrue(classes.contains("agent-message-text-tool"), "工具输出应带 tool 配色类");
    }

    @Test
    void approvalCardPullsTheViewToTheBottomEvenAfterScrollingUp() throws Exception {
        var refs = showView();
        fillScrollableContent(refs);
        fx(() -> refs.scroll().setVvalue(0.0));
        pump();
        assertEquals(0.0, refs.scroll().getVvalue(), 0.001);

        fx(() -> refs.view().printCommandProposal("ls -la", "低风险", ok -> { }, cmd -> { }));
        pump();
        assertEquals(1.0, refs.scroll().getVvalue(), 0.001,
                "待审批卡片必须把视图拉回最底部，避免用户错过审批");
    }

    @Test
    void jumpToBottomRestoresTheLatestContent() throws Exception {
        var refs = showView();
        fillScrollableContent(refs);
        fx(() -> refs.scroll().setVvalue(0.0));
        pump();
        fx(() -> refs.view().jumpToBottom());
        pump();
        assertEquals(1.0, refs.scroll().getVvalue(), 0.001, "jumpToBottom 应定位到最底部");
    }

    private void fillScrollableContent(ViewRefs refs) throws Exception {
        for (int i = 0; i < 8; i++) {
            final int index = i;
            fx(() -> refs.view().printAssistant("第 " + index + " 条 " + "内容".repeat(30)));
            pump();
        }
        VBox transcript = (VBox) field(refs.view(), "transcript");
        double content = transcript.getLayoutBounds().getHeight();
        double viewport = refs.scroll().getViewportBounds().getHeight();
        assertTrue(content > viewport, "前置条件：内容需要超出视口");
    }

    private ViewRefs showView() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        AtomicReference<ScrollPane> scrollRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        fx(() -> {
            try {
                AgentConsoleView view = new AgentConsoleView(List.of(), line -> { }, () -> { }, () -> { });
                Scene scene = new Scene(view, 352, 400);
                scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
                Stage window = new Stage();
                window.setScene(scene);
                window.show();
                viewRef.set(view);
                scrollRef.set((ScrollPane) field(view, "scroll"));
                stageRef.set(window);
                this.stage = window;
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        pump();
        return new ViewRefs(viewRef.get(), scrollRef.get());
    }

    /** 收集转录区所有正文的样式类，用于断言角色配色按类区分。 */
    private List<String> textClassesOf(AgentConsoleView view) throws Exception {
        VBox transcript = (VBox) field(view, "transcript");
        List<String> classes = new ArrayList<>();
        for (TextFlow flow : flowsOf(transcript)) {
            for (Node node : flow.getChildren()) {
                if (node instanceof Text text) {
                    classes.addAll(text.getStyleClass());
                }
            }
        }
        return classes;
    }

    private List<TextFlow> flowsOf(Parent parent) {
        List<TextFlow> found = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof TextFlow flow) {
                found.add(flow);
            } else if (node instanceof Parent child) {
                found.addAll(flowsOf(child));
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

    private Object field(Object target, String name) throws Exception {
        Field declared = target.getClass().getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(target);
    }

    private record ViewRefs(AgentConsoleView view, ScrollPane scroll) {
    }
}
