package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.manager.NavigationManager;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.domain.FileInfo;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** File entry/target regressions: idempotent open, fixed standalone target, remote states, Agent header. */
class FileEntryWorkflowTest {

    @Test
    void separateFileWindowsKeepBothTargetsAndReleaseTheirControllers() throws Exception {
        NavigationManager manager = new NavigationManager();
        SSHService service = fakeService(Map.of("a", params("a.example", 22), "b", params("b.example", 2222)));
        try {
            fx(() -> {
                manager.openInNewWindow("file-manager-panel.fxml", "A", "files:a",
                        controller -> ((FileManagerPanelController) controller).bindStandaloneTarget(service, "a"));
                manager.openInNewWindow("file-manager-panel.fxml", "B", "files:b",
                        controller -> ((FileManagerPanelController) controller).bindStandaloneTarget(service, "b"));
            });
            fx(() -> {
                assertNotSame(manager.getController("files:a"), manager.getController("files:b"));
                assertEquals("a", get(manager.getController("files:a"), "currentTabId"));
                assertEquals("b", get(manager.getController("files:b"), "currentTabId"));
                @SuppressWarnings("unchecked") Map<String, Stage> stages = (Map<String, Stage>) get(manager, "auxiliaryStages");
                assertTrue(stages.get("files:a").getTitle().contains("a.example"));
                assertTrue(stages.get("files:b").getTitle().contains("b.example"));
                // Save the actual FXML rendering with isolated, disconnected test targets.
                Stage stage = stages.get("files:a");
                ((TabPane) get(manager.getController("files:a"), "fileModes")).getSelectionModel().select(1);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                try {
                    java.nio.file.Path output = java.nio.file.Path.of("target/shots/files-round-disconnected.png");
                    java.nio.file.Files.createDirectories(output.getParent());
                    javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(
                            stage.getScene().snapshot(null), null), "png", output.toFile());
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            });
        } finally {
            fx(() -> {
                @SuppressWarnings("unchecked") Map<String, Stage> stages = (Map<String, Stage>) get(manager, "auxiliaryStages");
                List.copyOf(stages.values()).forEach(Stage::close);
            });
        }
        fx(() -> {
            assertNull(manager.getController("files:a"));
            assertNull(manager.getController("files:b"));
        });
    }

    @Test
    void disconnectedSessionClearsOldFilesAndDisposalRemovesSubscription() throws Exception {
        AtomicReference<Runnable> listener = new AtomicReference<>();
        SSHService service = (SSHService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SSHService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addSessionStateListener")) listener.set((Runnable) args[0]);
                    if (method.getName().equals("removeSessionStateListener")) {
                        assertSame(listener.get(), args[0]); listener.set(null);
                    }
                    return null;
                });
        FileManagerPanelController panel = new FileManagerPanelController();
        fx(() -> {
            set(panel, "panelVisible", false);
            set(panel, "remotePlaceholder", new Label());
            panel.setSshService(service, "a");
            @SuppressWarnings("unchecked") javafx.collections.ObservableList<FileInfo> files =
                    (javafx.collections.ObservableList<FileInfo>) get(panel, "remoteFiles");
            files.add(new FileInfo("old.txt", "-rw-r--r--", 1, 0L));
        });
        assertNotNull(listener.get());
        listener.get().run();
        awaitPlaceholder(panel, I18n.tr("fm.remote.disconnected"));
        fx(() -> {
            assertTrue(((List<?>) get(panel, "remoteFiles")).isEmpty());
            assertEquals(I18n.tr("fm.remote.disconnected"), ((Label) get(panel, "remotePlaceholder")).getText());
            panel.dispose();
            panel.dispose();
            assertNull(listener.get());
        });
    }

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void mainFileEntryIsIdempotentAndLocalTabExplainsUnavailable() throws Exception {
        fx(() -> {
            TerminalPanelController controller = new TerminalPanelController();
            TabPane pane = new TabPane();
            set(controller, "terminalTabPane", pane);
            Tab ssh = new Tab("ssh");
            ssh.setId("ssh");
            VBox body = new VBox();
            body.getStyleClass().add("file-drawer-body");
            body.setManaged(false);
            body.setVisible(false);
            ssh.setContent(new StackPane(body));
            pane.getTabs().add(ssh);
            pane.getSelectionModel().select(ssh);

            assertEquals(TerminalPanelController.FileAreaState.CLOSED, controller.fileAreaState());
            AtomicReference<TerminalPanelController.FileAreaState> observed = new AtomicReference<>();
            controller.setFileAreaStateListener(() -> observed.set(controller.fileAreaState()));
            assertEquals(TerminalPanelController.DrawerResult.OPENED, controller.showFileDrawer());
            assertTrue(body.isManaged());
            assertTrue(body.isVisible());
            assertEquals(TerminalPanelController.FileAreaState.OPEN, controller.fileAreaState());
            assertEquals(TerminalPanelController.FileAreaState.OPEN, observed.get());
            // 主入口是幂等 show：再次进入不能把已展开的文件区收起。
            assertEquals(TerminalPanelController.DrawerResult.OPENED, controller.showFileDrawer());
            assertEquals(TerminalPanelController.FileAreaState.OPEN, controller.fileAreaState());

            Tab local = new Tab("local");
            local.setId("local");
            local.setContent(new VBox());
            pane.getTabs().add(local);
            pane.getSelectionModel().select(local);
            assertEquals(TerminalPanelController.DrawerResult.UNAVAILABLE, controller.showFileDrawer());
            assertEquals(TerminalPanelController.FileAreaState.UNAVAILABLE, controller.fileAreaState());
            assertFalse(controller.hasFileDrawer());

            MainViewController main = new MainViewController();
            Button fileButton = new Button();
            set(main, "btnFilesDrawer", fileButton);
            TerminalPanelController localController = new TerminalPanelController() {
                @Override public String getSelectedTabId() { return "local"; }
                @Override public ConnectionParams getSelectedConnectionParams() { return null; }
                @Override public FileAreaState fileAreaState() { return FileAreaState.UNAVAILABLE; }
            };
            set(main, "terminalPanelController", localController);
            invoke(main, "refreshFileEntryState", new Class<?>[0]);
            assertTrue(fileButton.isDisable());
            assertEquals(I18n.tr("main.filesUnavailableShort"), fileButton.getText());
            assertEquals(I18n.tr("main.filesLocalTab"), fileButton.getTooltip().getText());
        });
    }

    @Test
    void standaloneTargetIsCapturedAndNotReboundByLaterOpens() throws Exception {
        fx(() -> {
            FileManagerPanelController panel = new FileManagerPanelController();
            VBox root = new VBox();
            Label sessionLabel = new Label();
            Button standaloneButton = new Button();
            set(panel, "fileManagerArea", root);
            set(panel, "remoteSessionLabel", sessionLabel);
            set(panel, "openStandaloneButton", standaloneButton);
            set(panel, "panelVisible", false);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 800, 600));

            SSHService service = fakeService(Map.of(
                    "a", params("a.example", 22),
                    "b", params("b.example", 2222)));
            panel.bindStandaloneTarget(service, "a");
            assertEquals("a", get(panel, "currentTabId"));
            assertTrue(panel.isStandalone());
            assertTrue(panel.getTargetDisplay().contains("root@a.example:22"));
            assertTrue(sessionLabel.isManaged());
            assertFalse(standaloneButton.isManaged());
            assertTrue(stage.getTitle().contains("a.example"));

            // 已打开的独立窗口固定原会话；再次打开不能跟随成 B。
            panel.bindStandaloneTarget(service, "b");
            assertEquals("a", get(panel, "currentTabId"));
            assertTrue(panel.getTargetDisplay().contains("a.example"));
            assertFalse(panel.getTargetDisplay().contains("b.example"));
            panel.onWindowClosing();
            stage.close();
        });
    }

    @Test
    void remotePlaceholderDistinguishesDisconnectedLoadingEmptyAndFailed() throws Exception {
        AtomicReference<SSHConnectionManager> connection = new AtomicReference<>();
        AtomicReference<Supplier<List<FileInfo>>> listing = new AtomicReference<>(List::of);
        AtomicReference<CountDownLatch> gate = new AtomicReference<>();
        AtomicBoolean fail = new AtomicBoolean();
        SSHService service = (SSHService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SSHService.class}, (proxy, method, args) -> {
                    String tab = args != null && args.length > 0 && args[0] instanceof String s ? s : "a";
                    return switch (method.getName()) {
                        case "getTabConnection" -> connection.get();
                        case "getTabConnectionParams" -> params("a.example", 22);
                        case "getHostForTab" -> "a.example";
                        case "getUsernameForTab" -> "root";
                        case "getCurrentWorkingDirectoryForTab" -> "/same";
                        case "canonicalDirectoryForTab" -> args != null && args.length > 1 ? args[1] : "/same";
                        case "listFilesForTab" -> {
                            CountDownLatch wait = gate.get();
                            if (wait != null) wait.await(5, TimeUnit.SECONDS);
                            if (fail.get()) throw new IllegalStateException("listing failed");
                            yield FXCollections.observableArrayList(listing.get().get());
                        }
                        default -> null;
                    };
                });

        FileManagerPanelController panel = new FileManagerPanelController();
        fx(() -> {
            TableView<FileInfo> table = new TableView<>();
            Label placeholder = new Label();
            table.setPlaceholder(placeholder);
            set(panel, "remoteFileTable", table);
            set(panel, "remotePlaceholder", placeholder);
            set(panel, "statusLabel", new Label());
            set(panel, "pathField", new TextField("~"));
            set(panel, "sshService", service);
            set(panel, "currentTabId", "a");
            set(panel, "panelVisible", true);
        });

        connection.set(null);
        fx(() -> invoke(panel, "loadRemoteFiles", new Class<?>[0]));
        fx(() -> {});
        awaitPlaceholder(panel, I18n.tr("fm.remote.disconnected"));

        connection.set(new TestConnection(params("a.example", 22)));
        CountDownLatch loading = new CountDownLatch(1);
        gate.set(loading);
        fx(() -> invoke(panel, "loadRemoteFiles", new Class<?>[0]));
        awaitPlaceholder(panel, I18n.tr("fm.remote.loading"));
        loading.countDown();
        awaitPlaceholder(panel, I18n.tr("fm.remote.empty"));

        gate.set(null);
        fail.set(true);
        fx(() -> invoke(panel, "loadRemoteFiles", new Class<?>[0]));
        awaitPlaceholder(panel, I18n.tr("fm.remote.failed"));
        fx(panel::onWindowClosing);
    }

    @Test
    void agentHeaderUsesReadableMetadataOfDisplayedTab() throws Exception {
        fx(() -> {
            AgentWorkspaceController controller = new AgentWorkspaceController();
            AgentConsoleView console = new AgentConsoleView(List.of(), _ -> {}, () -> {}, () -> {});
            new Scene(console, 400, 600);
            console.applyCss();
            console.layout();
            set(controller, "console", console);
            Tab tab = new Tab("tab-1");
            tab.setId("tab-1");
            tab.getProperties().put(TerminalPanelController.CONNECTION_PARAMS_KEY, params("a.example", 22));
            tab.getProperties().put(TerminalPanelController.BASE_TITLE_KEY, "a.example · 1");

            invoke(controller, "updateTerminalStatus", new Class<?>[]{Tab.class}, tab);
            Label target = (Label) console.lookup(".agent-target-label");
            assertNotNull(target);
            assertTrue(target.getText().contains("root@a.example:22"));
            assertFalse(target.getText().contains("tab-1"));

            Tab other = new Tab("tab-2");
            other.setId("tab-2");
            other.getProperties().put(TerminalPanelController.CONNECTION_PARAMS_KEY, params("b.example", 22));
            other.getProperties().put(TerminalPanelController.BASE_TITLE_KEY, "b.example · 2");
            invoke(controller, "updateTerminalStatus", new Class<?>[]{Tab.class}, other);
            assertTrue(target.getText().contains("root@b.example:22"), "标题必须跟随当前显示的标签: " + target.getText());
            assertFalse(target.getText().contains("a.example"), "不得残留上一个标签的目标");
            controller.onWindowClosing();
        });
    }

    private static SSHService fakeService(Map<String, ConnectionParams> params) {
        return (SSHService) Proxy.newProxyInstance(
                FileEntryWorkflowTest.class.getClassLoader(), new Class<?>[]{SSHService.class},
                (proxy, method, args) -> {
                    String tab = args != null && args.length > 0 && args[0] instanceof String s ? s : "a";
                    return switch (method.getName()) {
                        case "getTabConnectionParams" -> params.get(tab);
                        case "getHostForTab" -> "host-" + tab;
                        case "getUsernameForTab" -> "root";
                        default -> null;
                    };
                });
    }

    private static ConnectionParams params(String host, int port) {
        ConnectionParams params = new ConnectionParams();
        params.setHost(host);
        params.setPort(port);
        params.setUsername("root");
        return params;
    }

    private static void awaitPlaceholder(FileManagerPanelController panel, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String actual = "";
        while (System.currentTimeMillis() < deadline) {
            actual = fxGet(() -> ((Label) get(panel, "remotePlaceholder")).getText());
            if (expected.equals(actual)) return;
            Thread.sleep(25);
        }
        fail("Expected placeholder '" + expected + "' but was '" + actual + "'");
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

    private static <T> T fxGet(Supplier<T> supplier) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        fx(() -> value.set(supplier.get()));
        return value.get();
    }

    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class TestConnection extends SSHConnectionManager {
        TestConnection(ConnectionParams params) {
            super(params.getHost(), params.getPort(), params.getUsername(), "test-only-password", null);
        }
        @Override public boolean isConnected() { return true; }
    }
}
