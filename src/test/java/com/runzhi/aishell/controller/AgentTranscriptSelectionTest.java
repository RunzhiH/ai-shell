package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Agent 对话支持鼠标拖选片段并用 Ctrl+C / 右键菜单复制。 */
class AgentTranscriptSelectionTest {
    private Locale oldLocale;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @BeforeEach
    void locale() { oldLocale = I18n.current(); Platform.runLater(() -> I18n.setLocale(Locale.ENGLISH, false)); }

    @AfterEach
    void restore() { Platform.runLater(() -> I18n.setLocale(oldLocale, false)); }

    @Test
    void dragSelectsMessageFragmentAndShortcutCopiesIt() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        AtomicReference<TextFlow> flowRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            String body = "排查步骤：先确认连接，再检查进程和日志。";
            view.printAssistant(body);
            Scene scene = new Scene(view, 352, 600);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/style.css").toExternalForm());
            view.applyCss();
            view.layout();
            Node box = view.lookup(".agent-message-flow");
            assertNotNull(box, "selectable message body must exist");
            assertTrue(box instanceof StackPane);
            flowRef.set((TextFlow) ((StackPane) box).getChildren().get(1));
            viewRef.set(view);
            assertNull(view.getSelectedMessageText(), "no selection before drag");
        });

        fx(() -> {
            TextFlow flow = flowRef.get();
            // 从开头附近按下，向右拖动一段距离，选区应为消息的非空前缀；
            // 无窗口环境中 screenToLocal 不可用，事件坐标退回本地坐标（见视图回退路径）。
            fireEvent(flow, MouseEvent.MOUSE_PRESSED, 2, 6);
            fireEvent(flow, MouseEvent.MOUSE_DRAGGED, 72, 6);
            fireEvent(flow, MouseEvent.MOUSE_RELEASED, 72, 6);
        });

        AtomicReference<String> selectedRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = viewRef.get();
            String selected = view.getSelectedMessageText();
            assertNotNull(selected, "drag must leave a selection");
            assertFalse(selected.isEmpty());
            String body = "排查步骤：先确认连接，再检查进程和日志。";
            assertTrue(body.startsWith(selected), "selection must be a prefix fragment: " + selected);
            selectedRef.set(selected);
            // Ctrl+C 复制选区
            view.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, true, false, false));
        });
        // 与产品一致：FX 剪贴板不可用时 copyText 回退 AWT；读取也按同样顺序兜底。
        long deadline = System.currentTimeMillis() + 5000;
        String clipboard = null;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<String> snapshot = new AtomicReference<>();
            CountDownLatch read = new CountDownLatch(1);
            Platform.runLater(() -> { snapshot.set(systemClipboardText()); read.countDown(); });
            assertTrue(read.await(5, TimeUnit.SECONDS));
            clipboard = snapshot.get();
            if (selectedRef.get().equals(clipboard)) break;
            Thread.sleep(80);
        }
        assertEquals(selectedRef.get(), clipboard, "Ctrl+C must copy the selected fragment");
        fx(() -> {
            // 无拖选的点击 clears 选区，之后 Ctrl+C 不再复制旧选区（交还给输入框默认行为）
            fireEvent(flowRef.get(), MouseEvent.MOUSE_PRESSED, 2, 6);
            fireEvent(flowRef.get(), MouseEvent.MOUSE_RELEASED, 2, 6);
            assertNull(viewRef.get().getSelectedMessageText());
        });
    }

    @Test
    void transcriptStaysSingleSurfaceWithoutTextAreaBodies() throws Exception {
        AtomicReference<AgentConsoleView> viewRef = new AtomicReference<>();
        fx(() -> {
            AgentConsoleView view = new AgentConsoleView(List.of(), line -> {}, () -> {}, () -> {});
            view.printUser("用户消息");
            view.printAssistant("助手消息");
            view.printTechnical(AgentConsoleView.Role.THINK, "技术消息".repeat(40));
            new Scene(view, 352, 600);
            view.applyCss();
            view.layout();
            viewRef.set(view);
        });
        fx(() -> {
            AgentConsoleView view = viewRef.get();
            javafx.scene.layout.VBox transcript = (javafx.scene.layout.VBox)
                    view.lookup(".agent-message").getParent();
            assertTrue(transcript.lookupAll(".text-area").isEmpty(),
                    "message bodies must not introduce TextArea surfaces");
            assertTrue(transcript.lookupAll(".scroll-pane").isEmpty(),
                    "transcript must stay the only scroll surface");
            assertFalse(transcript.lookupAll(".agent-message-flow").isEmpty(),
                    "bodies must be selectable");
        });
    }

    private void fireEvent(TextFlow flow, javafx.event.EventType<MouseEvent> type, double localX, double localY) {
        MouseEvent event = new MouseEvent(type, localX, localY, localX + 500, localY + 100,
                MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null);
        javafx.event.Event.fireEvent(flow, event);
    }

    /** 与 ClipboardUtils 一致：先读 JavaFX 剪贴板，为空时回退 AWT。 */
    private static String systemClipboardText() {
        String fx = Clipboard.getSystemClipboard().getString();
        if (fx != null && !fx.isEmpty()) return fx;
        try {
            java.awt.datatransfer.Transferable content =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (content != null && content.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                return (String) content.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
