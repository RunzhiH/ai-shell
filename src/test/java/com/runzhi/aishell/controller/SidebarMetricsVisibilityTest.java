package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ThemeManager;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.impl.SSHServiceImpl;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** Sidebar metrics are page/session scoped; identity labels and tree remain in place. */
class SidebarMetricsVisibilityTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void metricsShowOnlyOnTerminalPageWithConnectedSessionAndIdentityRemains() throws Exception {
        fx(() -> {
            MainViewController main = new MainViewController();
            VBox metrics = new VBox();
            Label title = new Label("identity");
            Label name = new Label("root@a");
            set(main, "metricsGroup", metrics);
            set(main, "activeSessionTitle", title);
            set(main, "activeSessionName", name);

            SSHServiceImpl service = new SSHServiceImpl();
            SelectedTerminal terminal = new SelectedTerminal();
            terminal.id = "a";
            terminal.params = params("a.example");
            TestConnection connection = new TestConnection(terminal.params);
            service.getTabConnectionParams().put("a", terminal.params);
            service.getTabConnections().put("a", connection);
            set(main, "sshService", service);
            set(main, "terminalPanelController", terminal);

            setPageContext(main, "Terminal", true);
            assertTrue(metrics.isVisible());
            assertTrue(metrics.isManaged());
            assertEquals("identity", title.getText());
            assertEquals("root@a", name.getText());

            setPageContext(main, "Monitor", false);
            assertFalse(metrics.isVisible());
            assertFalse(metrics.isManaged());
            // 页面切换只隐藏指标组，当前目标身份仍保留。
            assertEquals("identity", title.getText());
            assertEquals("root@a", name.getText());
            assertTrue(title.isManaged());
            assertTrue(name.isManaged());
            // 旧会话通知不能把非终端页的指标重新撑开。
            refreshVisibility(main);
            assertFalse(metrics.isManaged());

            setPageContext(main, "Terminal", true);
            assertTrue(metrics.isManaged());

            connection.connected = false;
            refreshVisibility(main);
            assertFalse(metrics.isManaged());

            connection.connected = true;
            refreshVisibility(main);
            assertTrue(metrics.isManaged());

            terminal.id = "local";
            terminal.params = null;
            setPageContext(main, "Terminal", true);
            assertFalse(metrics.isManaged());

            terminal.id = "a";
            terminal.params = params("a.example");
            connection.connected = false;
            setPageContext(main, "Terminal", true);
            assertFalse(metrics.isManaged());
        });
    }

    @Test
    void fxmlStartsWithMetricsHiddenButKeepsIdentityLabels() throws Exception {
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/runzhi/aishell/main-view.fxml"), I18n.bundle());
            loader.setControllerFactory(type -> new MainViewController() {
                @Override public void initialize(URL location, ResourceBundle resources) { }
            });
            Parent root = loader.load();
            assertNotNull(root);
            VBox metrics = (VBox) loader.getNamespace().get("metricsGroup");
            Label title = (Label) loader.getNamespace().get("activeSessionTitle");
            assertFalse(metrics.isVisible());
            assertFalse(metrics.isManaged());
            assertNotNull(title);
            assertTrue(title.isVisible());
        });
    }

    @Test
    void writesIsolatedUnconnectedLayoutPreviews() throws Exception {
        Preferences prefs = Preferences.userNodeForPackage(TerminalPanelController.class);
        String savedAgentOpen = prefs.get("terminal.agent.open", null);
        prefs.remove("terminal.agent.open");
        try {
            fx(() -> {
                FXMLLoader terminalMain = loadMainView();
                StackPane terminalContent = (StackPane) terminalMain.getNamespace().get("contentArea");
                FXMLLoader terminalLoader = new FXMLLoader(
                        getClass().getResource("/com/runzhi/aishell/terminal-panel.fxml"), I18n.bundle());
                Parent terminalPanel = terminalLoader.load();
                TerminalPanelController terminalController = terminalLoader.getController();
                try {
                    VBox agentHost = (VBox) terminalLoader.getNamespace().get("agentWorkspaceHost");
                    assertFalse(agentHost.isVisible());
                    assertFalse(agentHost.isManaged());
                    terminalContent.getChildren().setAll(terminalPanel);
                    setPageContext((MainViewController) terminalMain.getController(),
                            I18n.tr("main.terminal"), true);
                    setActiveNav((MainViewController) terminalMain.getController(),
                            (javafx.scene.control.Button) terminalMain.getNamespace().get("navTerminal"));
                    snapshot(terminalMain.getRoot(), Path.of("target", "shots", "layout-round-terminal-empty.png"));
                } finally {
                    terminalController.onWindowClosing();
                }

                FXMLLoader monitorMain = loadMainView();
                StackPane monitorContent = (StackPane) monitorMain.getNamespace().get("contentArea");
                Parent monitorPanel = new FXMLLoader(
                        getClass().getResource("/com/runzhi/aishell/monitor-panel.fxml"), I18n.bundle()).load();
                monitorContent.getChildren().setAll(monitorPanel);
                Label title = (Label) monitorMain.getNamespace().get("activeSessionTitle");
                Label name = (Label) monitorMain.getNamespace().get("activeSessionName");
                title.setText(I18n.tr("main.activeSession", "root@preview-host"));
                name.setText("root@preview-host:22");
                setPageContext((MainViewController) monitorMain.getController(), I18n.tr("main.monitor"), false);
                setActiveNav((MainViewController) monitorMain.getController(),
                        (javafx.scene.control.Button) monitorMain.getNamespace().get("navMonitor"));
                VBox metrics = (VBox) monitorMain.getNamespace().get("metricsGroup");
                assertFalse(metrics.isVisible());
                assertFalse(metrics.isManaged());
                assertTrue(title.isManaged());
                snapshot(monitorMain.getRoot(), Path.of("target", "shots", "layout-round-monitor.png"));
            });
        } finally {
            if (savedAgentOpen == null) prefs.remove("terminal.agent.open");
            else prefs.put("terminal.agent.open", savedAgentOpen);
        }
    }

    @Test
    void sidebarActiveSessionScrollsTallContentAndShrinksWhenCollapsed() throws Exception {
        fx(() -> {
            FXMLLoader loader = loadMainView();
            MainViewController main = (MainViewController) loader.getController();
            Parent root = loader.getRoot();
            Map<String, Object> ns = loader.getNamespace();
            ScrollPane scroll = (ScrollPane) ns.get("activeSessionScroll");
            VBox metrics = (VBox) ns.get("metricsGroup");
            @SuppressWarnings("unchecked")
            TreeView<?> tree = (TreeView<?>) ns.get("serverTree");
            @SuppressWarnings("unchecked")
            TableView<Map<String, String>> storage = (TableView<Map<String, String>>) ns.get("storageTable");
            Label title = (Label) ns.get("activeSessionTitle");
            Label name = (Label) ns.get("activeSessionName");
            assertNotNull(scroll);
            assertNotNull(metrics);
            assertNotNull(tree);
            assertNotNull(storage);

            metrics.setVisible(true);
            metrics.setManaged(true);
            title.setText(I18n.tr("main.activeSession", "root@preview-host"));
            name.setText("root@preview-host:22");
            ((Label) ns.get("cpuLabel")).setText("12.5%");
            ((Label) ns.get("memoryLabel")).setText("512 / 1024 (50.0%)");
            ((Label) ns.get("loadLabel")).setText("0.42, 0.31, 0.20");
            ((Label) ns.get("netLabel")).setText("↓1.2 MB/s ↑300 KB/s");
            ((Label) ns.get("uptimeLabel")).setText("12 days");
            ((Label) ns.get("swapLabel")).setText("0 / 0 (0%)");
            invoke(main, "initializeSidebarStorageTable", new Class<?>[0]);
            storage.getItems().setAll(
                    Map.of("path", "/", "size", "10 / 100"),
                    Map.of("path", "/var", "size", "20 / 200"),
                    Map.of("path", "/home", "size", "30 / 300"));

            main.configureSidebarScroll();
            Scene scene = new Scene(root, 1200, 800);
            for (String css : ThemeManager.getInstance().getStylesheetsFor(ThemeManager.Theme.DARK)) {
                if (css != null) scene.getStylesheets().add(css);
            }
            root.resize(1200, 800);
            root.applyCss();
            TitledPane more = (TitledPane) root.lookup(".sidebar-details");
            assertNotNull(more);
            more.setAnimated(false);
            more.setExpanded(true);
            root.layout();

            assertTrue(scroll.getContent().getBoundsInLocal().getHeight()
                            > scroll.getViewportBounds().getHeight(),
                    "expanded metrics must exceed the scroll viewport");
            assertTrue(tree.getHeight() >= 120, "server tree lost its usable height: " + tree.getHeight());

            scroll.setVvalue(0);
            scroll.layout();
            snapshotScene(scene, Path.of("target", "shots", "sidebar-scroll-top.png"));
            scroll.setVvalue(1);
            scroll.layout();
            assertEquals(1.0d, scroll.getVvalue(), 0.001d);
            Bounds viewport = scroll.localToScene(scroll.getViewportBounds());
            Bounds tableBounds = storage.localToScene(storage.getBoundsInLocal());
            assertTrue(tableBounds.getMaxY() <= viewport.getMaxY() + 2,
                    "bottom of the disk table is not reachable by scrolling");
            snapshotScene(scene, Path.of("target", "shots", "sidebar-scroll-bottom.png"));
            double tallScrollHeight = scroll.getHeight();

            root.resize(1000, 600);
            root.applyCss();
            root.layout();
            assertTrue(scroll.getContent().getBoundsInLocal().getHeight()
                            > scroll.getViewportBounds().getHeight(),
                    "short window must keep the active block scrollable");
            assertTrue(tree.getHeight() >= 119, "short window squeezed the server tree: " + tree.getHeight());
            scroll.setVvalue(0);
            scroll.layout();

            // 收起更多信息并隐藏指标后，滚动块按自然内容缩回，身份仍保留。
            more.setExpanded(false);
            metrics.setVisible(false);
            metrics.setManaged(false);
            root.resize(1200, 800);
            root.applyCss();
            root.layout();
            assertTrue(scroll.getHeight() < tallScrollHeight,
                    "collapsed active block should release its scroll height");
            assertTrue(title.isVisible());
            assertTrue(title.isManaged());
            assertTrue(name.isVisible());
        });
    }

    private static FXMLLoader loadMainView() throws Exception {
        FXMLLoader loader = new FXMLLoader(
                SidebarMetricsVisibilityTest.class.getResource("/com/runzhi/aishell/main-view.fxml"), I18n.bundle());
        loader.setControllerFactory(type -> new MainViewController() {
            @Override public void initialize(URL location, ResourceBundle resources) { }
        });
        loader.load();
        return loader;
    }

    /** Isolated preview only: no SSH service, no session, no window shown. */
    private static void snapshot(Parent root, Path target) throws Exception {
        Scene scene = new Scene(root, 1216, 800);
        for (String css : ThemeManager.getInstance().getStylesheetsFor(ThemeManager.Theme.DARK)) {
            if (css != null && !scene.getStylesheets().contains(css)) scene.getStylesheets().add(css);
        }
        root.resize(1216, 800);
        root.applyCss();
        root.layout();
        snapshotScene(scene, target);
    }

    private static void snapshotScene(Scene scene, Path target) throws Exception {
        WritableImage image = scene.snapshot(null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        Files.createDirectories(target.getParent());
        assertTrue(ImageIO.write(buffered, "png", target.toFile()), "Could not write preview: " + target);
    }

    private static void setPageContext(MainViewController main, String label, boolean terminal) {
        invoke(main, "setPageContext", new Class<?>[]{String.class, boolean.class}, label, terminal);
    }

    private static void refreshVisibility(MainViewController main) {
        invoke(main, "refreshSidebarMetricsVisibility", new Class<?>[0]);
    }

    private static void setActiveNav(MainViewController main, javafx.scene.control.Button button) {
        invoke(main, "setActiveNav", new Class<?>[]{javafx.scene.control.Button.class}, button);
    }

    private static ConnectionParams params(String host) {
        ConnectionParams params = new ConnectionParams();
        params.setHost(host);
        params.setPort(22);
        params.setUsername("root");
        return params;
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = findMethod(target.getClass(), name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Private targets are declared on MainViewController, not on its anonymous test subclass. */
    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue with the declaring superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                // Continue with the declaring superclass.
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    private static void fx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
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
