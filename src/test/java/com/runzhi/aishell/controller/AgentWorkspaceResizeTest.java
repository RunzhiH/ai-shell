package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** Agent 工作区宽度：overlay 抽屉模式可拖动、钳制与持久化；SPLIT 仍由分隔条负责。 */
class AgentWorkspaceResizeTest {
    private static final Preferences PREFS = Preferences.userNodeForPackage(TerminalPanelController.class);
    private static final String OVERLAY_KEY = "terminal.agent.overlay.width";
    private static final String SPLIT_KEY = "terminal.agent.split";
    private String oldOpen, oldSplit, oldWidth;
    private Locale oldLocale;
    private TerminalPanelController controller;
    private VBox host;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @BeforeEach
    void create() throws Exception {
        oldOpen = PREFS.get("terminal.agent.open", null);
        oldSplit = PREFS.get(SPLIT_KEY, null);
        oldWidth = PREFS.get(OVERLAY_KEY, null);
        oldLocale = I18n.current();
        PREFS.putBoolean("terminal.agent.open", false);
        PREFS.remove(OVERLAY_KEY);
        fx(() -> {
            I18n.setLocale(Locale.ENGLISH, false);
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-panel.fxml"));
            loader.setResources(I18n.bundle());
            try { loader.load(); }
            catch (java.io.IOException failure) { throw new AssertionError("terminal-panel.fxml failed to load", failure); }
            controller = loader.getController();
            host = (VBox) loader.getNamespace().get("agentWorkspaceHost");
            controller.getTerminalStack().resize(1000, 700);
        });
    }

    @AfterEach
    void release() throws Exception {
        fx(() -> { controller.onWindowClosing(); I18n.setLocale(oldLocale, false); });
        restore("terminal.agent.open", oldOpen);
        restore(SPLIT_KEY, oldSplit);
        restore(OVERLAY_KEY, oldWidth);
    }

    private void restore(String key, String saved) { if (saved == null) PREFS.remove(key); else PREFS.put(key, saved); }

    @Test
    void overlayUsesSavedWidthWithinClamps() throws Exception {
        fx(() -> {
            PREFS.putDouble(OVERLAY_KEY, 600);
            controller.applyWorkspaceLayout(1280);   // OVERLAY 区间
            controller.showAgentWorkspace();
            assertEquals(600, host.getPrefWidth(), 1, "saved overlay width must be restored");
        });
    }

    @Test
    void savedOverlayWidthIsClamped() throws Exception {
        fx(() -> {
            PREFS.putDouble(OVERLAY_KEY, 2000);
            controller.applyWorkspaceLayout(1280);
            controller.showAgentWorkspace();
            assertEquals(760, host.getPrefWidth(), 1, "width must clamp to stackWidth-240");
            PREFS.putDouble(OVERLAY_KEY, 100);
            controller.toggleAgentWorkspace();      // 收起
            controller.showAgentWorkspace();        // 再次展开，按新保存值钳制
            assertEquals(280, host.getPrefWidth(), 1, "width must clamp to the minimum");
        });
    }

    @Test
    void dragLeftEdgeWidensTheOverlayAndPersists() throws Exception {
        fx(() -> {
            controller.applyWorkspaceLayout(1280);
            controller.showAgentWorkspace();
            assertEquals(352, host.getPrefWidth(), 1, "default overlay width is 352");
            fireEvent(MouseEvent.MOUSE_PRESSED, 2, 1000);
            fireEvent(MouseEvent.MOUSE_DRAGGED, 2, 950);
            assertEquals(402, host.getPrefWidth(), 1, "dragging left must widen by the delta");
            fireEvent(MouseEvent.MOUSE_RELEASED, 2, 950);
            assertEquals(402, PREFS.getDouble(OVERLAY_KEY, -1), 1, "release must persist the width");
        });
    }

    @Test
    void splitModeKeepsDividerAndOverlayWidthDoesNotLeakIn() throws Exception {
        fx(() -> {
            PREFS.putDouble(OVERLAY_KEY, 600);
            controller.applyWorkspaceLayout(1600);   // SPLIT 区间
            controller.showAgentWorkspace();
            assertEquals(javafx.scene.layout.Region.USE_COMPUTED_SIZE, host.getPrefWidth(), 0.001,
                    "split mode must give the divider full control");
            assertFalse(controller.getTerminalStack().getChildren().contains(host));
            assertTrue(controller.getTerminalSplitPane().getItems().contains(host));
        });
    }

    private void fireEvent(javafx.event.EventType<MouseEvent> type, double localX, double screenX) {
        MouseEvent event = new MouseEvent(type, localX, 10, screenX, 100, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true, null);
        javafx.event.Event.fireEvent(host, event);
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
