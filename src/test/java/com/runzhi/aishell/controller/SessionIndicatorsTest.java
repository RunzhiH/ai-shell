package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Regression checks use isolated sessions, without connecting to real servers. */
class SessionIndicatorsTest {
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test void currentTargetTracksSelectionAndClearsWithoutSshTarget() throws Exception {
        fx(() -> {
            SSHServiceImpl service = new SSHServiceImpl();
            SelectedTerminal terminal = new SelectedTerminal();
            MainViewController main = new MainViewController();
            Label host = new Label(), target = new Label(), health = new Label(), dot = new Label();
            field(main, "sshService", service);
            field(main, "terminalPanelController", terminal);
            field(main, "currentHostLabel", host);
            field(main, "statusHostLabel", target);
            field(main, "statusHealthLabel", health);
            field(main, "hostStatusDot", dot);

            terminal.id = "a"; terminal.params = params("a.example", 22);
            service.getTabConnections().put("a", new TestConnection(terminal.params));
            refresh(main);
            assertEquals("root@a.example:22", host.getText());
            assertEquals(I18n.tr("main.connected"), health.getText());

            terminal.id = "b"; terminal.params = params("b.example", 2222);
            refresh(main);
            assertEquals("root@b.example:2222", host.getText());
            assertEquals(host.getText(), target.getText());
            assertEquals(host.getText(), host.getTooltip().getText());
            assertEquals(I18n.tr("main.disconnected"), health.getText());

            terminal.id = "local"; terminal.params = null;
            refresh(main);
            assertEquals(I18n.tr("main.disconnected"), host.getText());
            assertEquals("—", target.getText());
            assertFalse(host.getTooltip().getText().contains("b.example"));
            terminal.id = null;
            refresh(main);
            assertEquals("—", target.getText());
        });
    }

    @Test void configurationRemainsActiveAfterOneOfTwoSessionsCloses() {
        SSHServiceImpl service = new SSHServiceImpl();
        ConnectionParams config = params("same.example", 22);
        service.getTabConnectionParams().put("one", config);
        service.getTabConnectionParams().put("two", params("same.example", 22));
        service.getTabConnectionParams().put("other-port", params("same.example", 2222));
        service.getTabConnections().put("one", new TestConnection(config));
        service.getTabConnections().put("two", new TestConnection(config));
        service.getTabConnections().put("other-port", new TestConnection(config));
        assertEquals(2, service.activeSessionCount(config));
        service.removeTabConnection("one");
        assertEquals(1, service.activeSessionCount(config));
        service.removeTabConnection("two");
        assertEquals(0, service.activeSessionCount(config));
        assertEquals(1, service.activeSessionCount(params("same.example", 2222)));
    }

    private static ConnectionParams params(String host, int port) {
        ConnectionParams params = new ConnectionParams();
        params.setHost(host); params.setPort(port); params.setUsername("root");
        return params;
    }

    private static void refresh(MainViewController main) {
        try {
            Method method = MainViewController.class.getDeclaredMethod("refreshCurrentTargetDisplay");
            method.setAccessible(true); method.invoke(main);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void field(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true); field.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static final class SelectedTerminal extends TerminalPanelController {
        String id;
        ConnectionParams params;
        @Override public String getSelectedTabId() { return id; }
        @Override public ConnectionParams getSelectedConnectionParams() { return params; }
    }

    private static final class TestConnection extends SSHConnectionManager {
        boolean connected = true;
        TestConnection(ConnectionParams params) {
            super(params.getHost(), params.getPort(), params.getUsername(), "test-only-password", null);
        }
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnect() { connected = false; }
    }
}
