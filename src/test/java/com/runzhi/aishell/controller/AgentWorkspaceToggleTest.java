package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import static org.junit.jupiter.api.Assertions.*;

/** Real FXML toggle state must match visibility across every workspace entry point. */
class AgentWorkspaceToggleTest {
    private static final Preferences PREFS=Preferences.userNodeForPackage(TerminalPanelController.class);
    private String oldOpen,oldSplit; private Locale oldLocale;
    private TerminalPanelController controller;
    private ToggleButton toggle; private Button close; private VBox host;
    private final String targetId="toggle-test-"+UUID.randomUUID();
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }
    private void fx(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.runLater(() -> { try { action.run(); } catch(Throwable error) { failure.set(error); } finally { done.countDown(); } });
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(failure.get()!=null) throw new AssertionError(failure.get());
    }
    private FXMLLoader load() {
        try {
            FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/terminal-panel.fxml"));
            loader.setResources(I18n.bundle()); loader.load(); return loader;
        } catch(Exception failure) { throw new AssertionError(failure); }
    }
    @BeforeEach void create() throws Exception {
        oldOpen=PREFS.get("terminal.agent.open",null); oldSplit=PREFS.get("terminal.agent.split",null);
        oldLocale=I18n.current(); PREFS.putBoolean("terminal.agent.open",false);
        fx(() -> {
            I18n.setLocale(Locale.ENGLISH,false); FXMLLoader loader=load(); controller=loader.getController();
            toggle=(ToggleButton)loader.getNamespace().get("btnAgentWorkspace");
            close=(Button)loader.getNamespace().get("btnHideAgentWorkspace");
            host=(VBox)loader.getNamespace().get("agentWorkspaceHost");
        });
    }
    @AfterEach void release() throws Exception {
        fx(() -> { controller.onWindowClosing(); AgentSessionTracker.getInstance().clear(targetId); I18n.setLocale(oldLocale,false); });
        if(oldOpen==null) PREFS.remove("terminal.agent.open"); else PREFS.put("terminal.agent.open",oldOpen);
        if(oldSplit==null) PREFS.remove("terminal.agent.split"); else PREFS.put("terminal.agent.split",oldSplit);
    }
    private void assertState(boolean open) {
        assertNotNull(toggle,"FXML must inject the stateful toggle");
        assertEquals(open,host.isVisible()); assertEquals(open,host.isManaged()); assertEquals(open,toggle.isSelected());
        assertEquals(AccessibleRole.TOGGLE_BUTTON,toggle.getAccessibleRole());
        String expected=I18n.tr(open?"workbench.agent.hide":"workbench.agent.show");
        assertEquals(expected,toggle.getAccessibleHelp()); assertEquals(expected,toggle.getTooltip().getText());
    }
    @Test void savedClosedStateHasNormalSurfaceAndAccessibleToggleRole() throws Exception { fx(() -> assertState(false)); }

    @Test void noSavedPreferenceDefaultsClosedAndLayoutChangesDoNotWritePreference() throws Exception {
        fx(() -> {
            PREFS.remove("terminal.agent.open");
            FXMLLoader loader = load();
            TerminalPanelController fresh = loader.getController();
            try {
                VBox panel = (VBox) loader.getNamespace().get("agentWorkspaceHost");
                ToggleButton button = (ToggleButton) loader.getNamespace().get("btnAgentWorkspace");
                assertFalse(panel.isVisible());
                assertFalse(panel.isManaged());
                assertFalse(button.isSelected());
                assertNull(PREFS.get("terminal.agent.open", null),
                        "Loading a panel without a preference must not write one");
                fresh.applyWorkspaceLayout(1600);
                fresh.applyWorkspaceLayout(1280);
                assertNull(PREFS.get("terminal.agent.open", null),
                        "Responsive layout must not rewrite the Agent preference");
                Tab switched = new Tab("target");
                switched.setId(targetId);
                fresh.getTerminalTabPane().getTabs().add(switched);
                fresh.getTerminalTabPane().getSelectionModel().select(switched);
                assertNull(PREFS.get("terminal.agent.open", null),
                        "Selecting a tab must not rewrite the Agent preference");
            } finally {
                fresh.onWindowClosing();
            }
        });
    }
    @Test void toolbarAndCloseButtonKeepStateSynchronizedWithoutCancellingTask() throws Exception {
        fx(() -> {
            Tab target=new Tab("target"); target.setId(targetId); controller.getTerminalTabPane().getTabs().add(target);
            controller.getTerminalTabPane().getSelectionModel().select(target);
            AgentSessionTracker tracker=AgentSessionTracker.getInstance(); tracker.markRunning(targetId);
            tracker.record(new AgentActivityBus.Event(targetId,AgentActivityBus.Kind.PROPOSAL,"pending",Instant.now(),List.of(),null,
                    approved->{},UUID.randomUUID().toString()));
            toggle.fire(); assertState(true); close.fire(); assertState(false);
            assertTrue(tracker.isRunning(targetId)); assertEquals(1,tracker.pendingApprovals(targetId));
            assertSame(target,controller.getTerminalTabPane().getSelectionModel().getSelectedItem());
        });
    }
    @Test void programmaticOpenAndResponsiveLayoutPreserveToggleState() throws Exception {
        fx(() -> {
            controller.showAgentWorkspace(); assertState(true);
            controller.applyWorkspaceLayout(1600); assertState(true);
            controller.applyWorkspaceLayout(1280); assertState(true);
            toggle.fire(); assertState(false);
            controller.applyWorkspaceLayout(1600); assertState(false);
        });
    }
    @Test void reopeningPanelRestoresSavedToggleState() throws Exception {
        fx(() -> {
            toggle.fire(); assertState(true);
            FXMLLoader next=load(); TerminalPanelController reopened=next.getController();
            try {
                ToggleButton button=(ToggleButton)next.getNamespace().get("btnAgentWorkspace");
                VBox panel=(VBox)next.getNamespace().get("agentWorkspaceHost");
                assertTrue(button.isSelected()); assertTrue(panel.isVisible());
            } finally { reopened.onWindowClosing(); }
        });
    }
    @Test void smallWindowUsesAgentWorkspaceSwitchWithoutChangingSelection() throws Exception {
        fx(() -> {
            Tab target=new Tab("target"); target.setId(targetId); controller.getTerminalTabPane().getTabs().add(target);
            controller.getTerminalTabPane().getSelectionModel().select(target);
            controller.applyWorkspaceLayout(1000); controller.showAgentWorkspace();
            assertState(true); assertFalse(controller.getTerminalTabPane().isManaged());
            assertFalse(controller.getTerminalTabPane().isVisible());
            assertSame(target,controller.getTerminalTabPane().getSelectionModel().getSelectedItem());
            close.fire(); assertTrue(controller.getTerminalTabPane().isVisible()); assertTrue(controller.getTerminalTabPane().isManaged());
        });
    }
    @Test void overlayReservesFileActionsAndRestoresFullWidthWhenClosed() throws Exception {
        fx(() -> {
            VBox files=new VBox(); files.getStyleClass().add("file-drawer-body");
            Tab target=new Tab("files",new VBox(files)); target.setId(targetId); controller.getTerminalTabPane().getTabs().add(target);
            controller.getTerminalStack().resize(1000,700);
            controller.applyWorkspaceLayout(1280); controller.showAgentWorkspace();
            assertEquals(636,files.getMaxWidth(),1); assertTrue(controller.getTerminalTabPane().isVisible());
            assertTrue(host.getStyleClass().contains("agent-overlay"));
            close.fire(); assertEquals(Double.MAX_VALUE,files.getMaxWidth());
        });
    }
    @Test void outsideClickClosesOverlayWithoutConsumingTheFileEvent() throws Exception {
        fx(() -> {
            controller.applyWorkspaceLayout(1280); controller.showAgentWorkspace();
            var inside=new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,0,0,0,0,
                    javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,false,false,false,false,false,true,null);
            host.fireEvent(inside); assertState(true);
            var outside=new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,0,0,0,0,
                    javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,false,false,false,false,false,true,null);
            controller.getTerminalStack().fireEvent(outside); assertState(false); assertFalse(outside.isConsumed());
        });
    }

}
