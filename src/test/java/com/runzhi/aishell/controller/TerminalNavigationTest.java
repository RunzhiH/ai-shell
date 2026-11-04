package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.util.ParameterManager;
import com.runzhi.aishell.common.util.ViewSwitcher;
import com.runzhi.aishell.service.TerminalConnectionService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Terminal navigation must preserve sessions and never invoke the connection dialog. */
class TerminalNavigationTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    /** Run controller actions on FX, propagating failures to the test thread. */
    private void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void inject(Object target, String name, Object value) {
        try {
            Field field = target instanceof MainViewController
                    ? MainViewController.class.getDeclaredField(name)
                    : TerminalPanelController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Navigation fixture keeps real Tab identity/selection without SSH or FXML side effects. */
    private static class TerminalFixture extends TerminalPanelController {
        int creationRequests;
        @Override void createNewTerminalTab() { creationRequests++; }
        @Override public void createNewTerminalTabWithParams(com.runzhi.aishell.ssh.domain.ConnectionParams params) {
            creationRequests++;
            Tab tab=createConfiguredTerminalTab(params);
            getTerminalTabPane().getTabs().add(tab);
            getTerminalTabPane().getSelectionModel().select(tab);
        }
    }

    @Test void firstTreeActivationDoesNotBroadcastASecondCreationWhenTerminalIsHidden() throws Exception {
        checkTreeActivation(false);
    }

    @Test void firstTreeActivationDoesNotBroadcastASecondCreationWhenTerminalIsEmpty() throws Exception {
        checkTreeActivation(true);
    }

    private void checkTreeActivation(boolean visible) throws Exception {
        onFx(()->{
            var params=new com.runzhi.aishell.ssh.domain.ConnectionParams();
            params.setHost("example.test");params.setPort(22);params.setUsername("root");
            TabPane tabs=new TabPane(); TerminalFixture terminal=new TerminalFixture();
            inject(terminal,"terminalTabPane",tabs);
            VBox view=new VBox(tabs);view.getStyleClass().add("terminal-panel");
            StackPane content=new StackPane(visible?view:new VBox());
            RetainedView switcher=new RetainedView(content,view,terminal);
            MainViewController main=new MainViewController();
            inject(main,"contentArea",content);inject(main,"viewSwitcher",switcher);inject(main,"statusLabel",new Label());
            var item=new javafx.scene.control.TreeItem<>(params);
            var tree=new javafx.scene.control.TreeView<>(item);tree.getSelectionModel().select(item);
            inject(main,"serverTree",tree);
            java.util.concurrent.atomic.AtomicInteger broadcasts=new java.util.concurrent.atomic.AtomicInteger();
            javafx.collections.MapChangeListener<String,Object> observer=change->{
                if("connection_params".equals(change.getKey()) && change.wasAdded()) broadcasts.incrementAndGet();
            };
            var manager=ParameterManager.getInstance();manager.remove("connection_params");
            manager.getParameters().addListener(observer);
            try {
                assertTrue(main.activateSelectedTreeConnection());
                Tab first=tabs.getSelectionModel().getSelectedItem();
                assertEquals(1,tabs.getTabs().size());assertEquals(1,terminal.creationRequests);
                assertEquals(0,broadcasts.get(),"Direct tree activation must not enqueue a deferred second creator");
                assertNull(manager.get("connection_params",com.runzhi.aishell.ssh.domain.ConnectionParams.class));
                assertTrue(main.activateSelectedTreeConnection());
                assertEquals(1,tabs.getTabs().size());assertSame(first,tabs.getSelectionModel().getSelectedItem());
                assertEquals(1,terminal.creationRequests);
            } finally { manager.getParameters().removeListener(observer);manager.remove("connection_params"); }
        });
    }

    private static class RetainedView extends ViewSwitcher {
        final Parent view;
        final TerminalFixture controller;
        int loads;
        RetainedView(StackPane content, Parent view, TerminalFixture controller) {
            super(content);
            this.view = view;
            this.controller = controller;
        }
        @Override public <T> T getController(String path, Class<T> type) {
            return type.cast(controller);
        }
        @Override public <T> Parent switchTo(String path, String style, Class<T> type,
                                            Consumer<T> configure) {
            loads++;
            configure.accept(type.cast(controller));
            replaceContent(view, style);
            return view;
        }
    }

    @Test void clickingVisibleTerminalDoesNotReloadOrRequestConnection() throws Exception {
        checkNavigation(true);
    }

    @Test void returningFromAnotherPageDoesNotRequestNewConnection() throws Exception {
        checkNavigation(false);
    }

    private void checkNavigation(boolean alreadyVisible) throws Exception {
        onFx(() -> {
            Tab a = new Tab("SSH A"), b = new Tab("Local B");
            TabPane tabs = new TabPane(a, b);
            tabs.getSelectionModel().select(b);
            VBox terminal = new VBox(tabs);
            terminal.getStyleClass().add("terminal-panel");
            StackPane content = new StackPane(alreadyVisible ? terminal : new VBox());
            new Scene(content, 1000, 700); // Existing attached view used to trigger a reload.
            TerminalFixture fixture = new TerminalFixture();
            RetainedView switcher = new RetainedView(content, terminal, fixture);
            MainViewController main = new MainViewController();
            inject(main, "contentArea", content);
            inject(main, "viewSwitcher", switcher);
            inject(main, "statusLabel", new Label());
            Button navigation = new Button();
            inject(main, "navTerminal", navigation);
            main.openTerminal();
            main.openTerminal();
            assertEquals(alreadyVisible ? 0 : 1, switcher.loads);
            assertSame(terminal, content.getChildren().getFirst());
            assertEquals(java.util.List.of(a, b), tabs.getTabs());
            assertSame(b, tabs.getSelectionModel().getSelectedItem());
            assertEquals(0, fixture.creationRequests, "navigation must not open a connection dialog");
            assertTrue(navigation.getStyleClass().contains("active"));
        });
    }

    @Test void cancellingExplicitNewConnectionPreservesExistingTabs() throws Exception {
        onFx(() -> {
            Tab a = new Tab("SSH A"), b = new Tab("Local B");
            TabPane tabs = new TabPane(a, b);
            tabs.getSelectionModel().select(a);
            TerminalPanelController terminal = new TerminalPanelController();
            inject(terminal, "terminalTabPane", tabs);
            TerminalConnectionService cancelled = (TerminalConnectionService) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{TerminalConnectionService.class},
                    (proxy, method, args) -> null);
            inject(terminal, "terminalConnectionService", cancelled);
            ParameterManager.getInstance().remove("connection_params");
            terminal.createNewTerminalTab();
            assertEquals(java.util.List.of(a, b), tabs.getTabs());
            assertSame(a, tabs.getSelectionModel().getSelectedItem());
        });
    }
    @Test void independentAiChatDoesNotNavigateOrTouchTerminalTabs() throws Exception {
        onFx(() -> {
            try {
                Tab a=new Tab("SSH A"), b=new Tab("Local B");
                TabPane tabs=new TabPane(a,b); tabs.getSelectionModel().select(b);
                VBox terminal=new VBox(tabs); StackPane content=new StackPane(terminal);
                MainViewController main=new MainViewController();
                inject(main,"contentArea",content);
                class ChatNavigation extends com.runzhi.aishell.common.manager.NavigationManager {
                    String opened;
                    @Override public void openInNewWindow(String path,String title) { opened=path; }
                }
                ChatNavigation navigation=new ChatNavigation();
                var constructor=com.runzhi.aishell.common.ai.AIShellModuleManager.class.getDeclaredConstructor();
                constructor.setAccessible(true); var modules=constructor.newInstance();
                Field nav=modules.getClass().getDeclaredField("navigationManager"); nav.setAccessible(true); nav.set(modules,navigation);
                Field owner=MainViewController.class.getDeclaredField("moduleManager"); owner.setAccessible(true); owner.set(main,modules);
                main.openAIAssistant();
                assertEquals("ai-assistant-panel.fxml",navigation.opened);
                assertSame(terminal,content.getChildren().getFirst());
                assertEquals(java.util.List.of(a,b),tabs.getTabs());
                assertSame(b,tabs.getSelectionModel().getSelectedItem());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

}
