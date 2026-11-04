package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.common.manager.NavigationManager;
import com.runzhi.aishell.common.util.Cleanable;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Destroyed controllers must not retain global subscriptions or consume queued events. */
class ControllerLifecycleTest {
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }
    private static void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); } finally { done.countDown(); }
        });
        assertTrue(done.await(15,TimeUnit.SECONDS));
        if (error.get()!=null) throw new AssertionError(error.get());
    }
    private static Object field(Object target,String name) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static int subscriptions() {
        try { Field f=AgentActivityBus.class.getDeclaredField("LISTENERS"); f.setAccessible(true); return ((List<?>)f.get(null)).size(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private AgentWorkspaceController workspace() {
        try {
            FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/agent-workspace.fxml"));
            Parent root=loader.load(); assertNotNull(root); return loader.getController();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Test void destroyingWorkspaceUnsubscribesAndIgnoresQueuedEvents() throws Exception {
        AgentWorkspaceController[] controller = new AgentWorkspaceController[1];
        fx(() -> {
            int initial=subscriptions(); controller[0]=workspace(); assertEquals(initial+1,subscriptions());
            AgentActivityBus.publish("lifecycle-test",AgentActivityBus.Kind.STATUS,"queued before disposal");
            controller[0].onWindowClosing(); controller[0].onWindowClosing();
            assertEquals(initial,subscriptions());
            AgentActivityBus.publish("lifecycle-test",AgentActivityBus.Kind.STATUS,"after disposal");
        });
        fx(() -> assertTrue(((Map<?,?>)field(controller[0],"history")).isEmpty()));
    }
    @Test void terminalOwnsNestedWorkspaceAndDisposesOnce() throws Exception {
        fx(() -> {
            int initial=subscriptions(); AgentWorkspaceController child=workspace();
            TerminalPanelController terminal=new TerminalPanelController();
            terminal.setAgentWorkspaceController(child);
            AtomicInteger releases=new AtomicInteger(); terminal.setAgentStateSubscription(releases::incrementAndGet);
            terminal.onWindowClosing(); terminal.onWindowClosing();
            assertEquals(1,releases.get()); assertEquals(initial,subscriptions());
        });
    }
    @SuppressWarnings("unchecked")
    @Test void clearingCacheReleasesEachControllerOnceAndContinuesAfterFailure() throws Exception {
        fx(() -> {
            NavigationManager manager=new NavigationManager(); AtomicInteger releases=new AtomicInteger();
            Map<String,Object> controllers=(Map<String,Object>)field(manager,"controllers");
            Cleanable healthy=releases::incrementAndGet;
            controllers.put("one",healthy); controllers.put("alias",healthy);
            controllers.put("broken",(Cleanable)()->{ throw new IllegalStateException("simulated cleanup failure"); });
            manager.clearCache(); manager.clearCache();
            assertEquals(1,releases.get()); assertNull(manager.getController("one"));
        });
    }
}
