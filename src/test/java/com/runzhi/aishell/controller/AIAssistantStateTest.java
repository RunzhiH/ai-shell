package com.runzhi.aishell.controller;

import com.runzhi.aishell.ai.AIAdapter;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ai.stream.AIRequestHandle;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Request callbacks and model selections must retain their original identities. */
class AIAssistantStateTest {
    private static class Fixture extends AIAssistantPanelController {
        AIConfig switched; int switches;
        @Override public void initialize(URL location,ResourceBundle resources) { }
        @Override void applyConfigChange(AIConfig config) { switched=config; switches++; }
    }
    private Fixture controller; private Parent root;
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    private static void fx(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(() -> { try { action.run(); } catch(Throwable failure) { error.set(failure); } finally { done.countDown(); } });
        assertTrue(done.await(15,TimeUnit.SECONDS)); if(error.get()!=null) throw new AssertionError(error.get());
    }
    @BeforeEach void load() throws Exception {
        fx(() -> { try {
            controller=new Fixture();
            FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/ai-assistant-panel.fxml"));
            loader.setResources(I18n.bundle()); loader.setControllerFactory(type -> controller); root=loader.load();
        } catch(Exception error) { throw new AssertionError(error); } });
    }
    @AfterEach void dispose() throws Exception { fx(controller::onWindowClosing); }
    private Object field(String name) {
        try { Field field=AIAssistantPanelController.class.getDeclaredField(name); field.setAccessible(true); return field.get(controller); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    private void call(String name) {
        try { Method method=AIAssistantPanelController.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(controller); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    private AIAdapter adapter(Supplier<String> response) {
        return (AIAdapter)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AIAdapter.class},
                (proxy,method,args) -> switch(method.getName()) {
                    case "sendQuery" -> response.get(); case "isConfigured" -> true; default -> "test";
                });
    }
    /** Hold the FX event queue until the worker has enqueued its terminal callback. */
    private void awaitWorker() {
        AIRequestHandle handle=(AIRequestHandle)field("activeRequest");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!handle.isDone() && System.nanoTime()<deadline) {
            try { Thread.sleep(5); } catch(InterruptedException interrupted) { throw new AssertionError(interrupted); }
        }
        assertTrue(handle.isDone());
    }
    private String transcript() {
        return controller.getTerminalOutput().getChildren().stream().filter(Text.class::isInstance)
                .map(Text.class::cast).map(Text::getText).reduce("",String::concat);
    }
    @Test @SuppressWarnings("unchecked") void duplicateModelNamesSelectExactConfigAndRefreshHasNoSideEffects() throws Exception {
        fx(() -> {
            AIConfig one=new AIConfig("first","OpenAI","dummy-one","http://localhost","same-model");
            AIConfig two=new AIConfig("second","OpenAI","dummy-two","http://localhost","same-model");
            controller.populateModels(List.of(one,two),one);
            assertEquals(0,controller.switches);
            ChoiceBox<AIConfig> models=(ChoiceBox<AIConfig>)field("modelChoice");
            assertEquals("second · same-model",models.getConverter().toString(two));
            assertFalse(models.getConverter().toString(two).contains("dummy-two"));
            models.setValue(two); assertSame(two,controller.switched);
        });
    }
    @Test void clearDropsAlreadyQueuedReplyAndCommandCard() throws Exception {
        fx(() -> {
            controller.startRequest("request",adapter(() -> "stale reply\n```bash\nls -la\n```"));
            awaitWorker(); call("clearChat");
        });
        fx(() -> {
            assertTrue(controller.getTerminalOutput().getChildren().isEmpty());
            assertFalse(((TextField)field("commandInput")).isDisabled());
        });
    }
    @Test void previousCompletionCannotUnlockNewRequest() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try {
            fx(() -> {
                controller.startRequest("old request",adapter(() -> "stale reply")); awaitWorker();
                controller.startRequest("new request",adapter(() -> {
                    try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return "fresh reply";
                }));
            });
            fx(() -> {
                assertTrue(((TextField)field("commandInput")).isDisabled());
                assertFalse(transcript().contains("stale reply"));
            });
            release.countDown(); fx(this::awaitWorker);
            fx(() -> { assertTrue(transcript().contains("fresh reply")); assertFalse(((TextField)field("commandInput")).isDisabled()); });
        } finally { release.countDown(); }
    }
    @Test void closingIgnoresQueuedReply() throws Exception {
        fx(() -> {
            controller.startRequest("request",adapter(() -> "stale reply")); awaitWorker(); controller.onWindowClosing();
        });
        fx(() -> assertFalse(transcript().contains("stale reply")));
    }
    @Test void replyUsesLightThemeTextInsteadOfFixedPaleColor() throws Exception {
        fx(() -> {
            Scene scene=new Scene(root,800,600);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/light-theme.css").toExternalForm());
            controller.startRequest("request",adapter(() -> "readable reply")); awaitWorker();
        });
        fx(() -> {
            root.applyCss(); root.layout();
            assertTrue(transcript().contains("readable reply"));
            for(var node:controller.getTerminalOutput().getChildren()) if(node instanceof Text text)
                assertEquals(Color.web("#292929"),text.getFill());
        });
    }
}
