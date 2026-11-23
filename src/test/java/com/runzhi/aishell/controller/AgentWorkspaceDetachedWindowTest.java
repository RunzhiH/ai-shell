package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
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

/** Agent 工作区独立窗口：非模态弹出、主面板与终端不受影响、关闭后挂回。 */
class AgentWorkspaceDetachedWindowTest {
    private static final Preferences PREFS = Preferences.userNodeForPackage(TerminalPanelController.class);
    private static final String[] WINDOW_KEYS = {
            "terminal.agent.window.x", "terminal.agent.window.y",
            "terminal.agent.window.width", "terminal.agent.window.height"};
    private String oldOpen;
    private final java.util.Map<String, String> oldWindowPrefs = new java.util.HashMap<>();
    private Locale oldLocale;
    private TerminalPanelController controller;
    private VBox host;
    private javafx.scene.control.SplitPane split;
    private javafx.scene.layout.StackPane stack;
    private javafx.scene.control.Button popOut;

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
        oldWindowPrefs.clear();
        for (String key : WINDOW_KEYS) oldWindowPrefs.put(key, PREFS.get(key, null));
        oldLocale = I18n.current();
        PREFS.putBoolean("terminal.agent.open", false);
        fx(() -> {
            I18n.setLocale(Locale.ENGLISH, false);
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-panel.fxml"));
            loader.setResources(I18n.bundle());
            try { loader.load(); }
            catch (java.io.IOException failure) { throw new AssertionError("terminal-panel.fxml failed to load", failure); }
            controller = loader.getController();
            host = (VBox) loader.getNamespace().get("agentWorkspaceHost");
            split = (javafx.scene.control.SplitPane) loader.getNamespace().get("terminalSplitPane");
            stack = (javafx.scene.layout.StackPane) loader.getNamespace().get("terminalStack");
            popOut = (javafx.scene.control.Button) loader.getNamespace().get("btnPopOutAgentWorkspace");
        });
    }

    @AfterEach
    void release() throws Exception {
        fx(() -> { controller.onWindowClosing(); I18n.setLocale(oldLocale, false); });
        if (oldOpen == null) PREFS.remove("terminal.agent.open");
        else PREFS.putBoolean("terminal.agent.open", Boolean.parseBoolean(oldOpen));
        for (String key : WINDOW_KEYS) {
            String saved = oldWindowPrefs.get(key);
            if (saved == null) PREFS.remove(key);
            else PREFS.put(key, saved);
        }
    }

    @Test
    void popOutCreatesNonModalWindowAndKeepsTerminalAvailable() throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        fx(() -> {
            controller.showAgentWorkspace();          // 内嵌展开
            controller.popOutAgentWorkspace();        // 弹出
            Stage stage = (Stage) host.getScene().getWindow();
            stageRef.set(stage);
            assertTrue(stage.isShowing(), "independent window must be shown");
            assertEquals(Modality.NONE, stage.getModality(), "window must stay non-modal");
            assertFalse(split.getItems().contains(host), "host must be removed from the split pane");
            assertFalse(stack.getChildren().contains(host), "host must be removed from the terminal stack");
            assertTrue(host.isVisible() && host.isManaged());
            // 主面板终端保持可交互
            assertTrue(controller.getTerminalTabPane().isVisible());
            assertTrue(controller.getTerminalTabPane().isManaged());
            assertNotNull(popOut.getTooltip().getText());
            assertEquals(I18n.tr("workbench.agent.dock"), popOut.getAccessibleText());
        });
        fx(() -> {
            // 收回（保持展开）
            controller.popOutAgentWorkspace();
            assertFalse(stageRef.get().isShowing(), "window must close when docking back");
            assertTrue(split.getItems().contains(host) || stack.getChildren().contains(host),
                    "host must return to the main panel");
            assertTrue(host.isVisible() && host.isManaged());
            assertEquals(I18n.tr("workbench.agent.popOut"), popOut.getAccessibleText());
        });
    }

    @Test
    void toggleWhileDetachedDocksBackAndHides() throws Exception {
        fx(() -> {
            controller.showAgentWorkspace();
            controller.popOutAgentWorkspace();
        });
        fx(() -> {
            controller.toggleAgentWorkspace();   // 分离态：收回并隐藏
            assertFalse(host.isVisible());
            assertFalse(host.isManaged());
        });
    }

    @Test
    void windowCloseRequestDocksBackWithoutLosingTheWorkspace() throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        fx(() -> {
            controller.popOutAgentWorkspace();
            stageRef.set((Stage) host.getScene().getWindow());
            Stage stage = stageRef.get();
            stage.getOnCloseRequest().handle(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        });
        fx(() -> {
            assertFalse(stageRef.get().isShowing());
            assertTrue(host.isVisible() && host.isManaged(), "docking via window close must keep the workspace open");
            assertTrue(split.getItems().contains(host) || stack.getChildren().contains(host));
        });
    }

    @Test
    void closingMainControllerDisposesTheDetachedWindow() throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        fx(() -> {
            controller.popOutAgentWorkspace();
            stageRef.set((Stage) host.getScene().getWindow());
        });
        fx(() -> controller.onWindowClosing());
        fx(() -> assertFalse(stageRef.get().isShowing(), "detached window must be closed with the main controller"));
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
