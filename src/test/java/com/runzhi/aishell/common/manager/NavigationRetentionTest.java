package com.runzhi.aishell.common.manager;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Re-entering a mounted page must retain the controller and user input. */
class NavigationRetentionTest {
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }
    private static void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @SuppressWarnings("unchecked")
    private static void cache(NavigationManager manager, String key, Parent page, Object controller) {
        try {
            Field views = NavigationManager.class.getDeclaredField("views"); views.setAccessible(true);
            ((Map<String,Parent>) views.get(manager)).put(key, page);
            Field controllers = NavigationManager.class.getDeclaredField("controllers"); controllers.setAccessible(true);
            ((Map<String,Object>) controllers.get(manager)).put(key, controller);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test void mountedContentIsReusedWithoutClearingInput() throws Exception {
        fx(() -> {
            NavigationManager manager = new NavigationManager();
            TextField input = new TextField("unsaved filter");
            StackPane page = new StackPane(input), destination = new StackPane(page);
            new Scene(destination);
            Object controller = new Object(); cache(manager,"cached.fxml",page,controller);
            try { assertSame(page,manager.loadPageForContainer("cached.fxml",destination)); }
            catch (Exception e) { throw new AssertionError(e); }
            assertSame(controller,manager.getController("cached.fxml"));
            assertEquals("unsaved filter",input.getText());
        });
    }
    @Test void repeatedContentNavigationDoesNotReplacePage() throws Exception {
        NavigationManager manager = new NavigationManager();
        StackPane[] destination = new StackPane[1], page = new StackPane[1];
        fx(() -> {
            page[0] = new StackPane(); destination[0] = new StackPane(page[0]); new Scene(destination[0]);
            cache(manager,"cached.fxml",page[0],new Object());
            manager.updateContentPane("cached.fxml",destination[0]);
        });
        fx(() -> assertSame(page[0],destination[0].getChildren().getFirst()));
    }
    @Test void rootNavigationReusesCurrentAndDetachedPages() throws Exception {
        NavigationManager manager = new NavigationManager(); Stage[] stage = new Stage[1];
        StackPane[] first = new StackPane[1], second = new StackPane[1];
        fx(() -> {
            stage[0] = new Stage(); first[0] = new StackPane(); second[0] = new StackPane();
            stage[0].setScene(new Scene(first[0])); manager.setPrimaryStage(stage[0]);
            cache(manager,"first.fxml",first[0],new Object()); cache(manager,"second.fxml",second[0],new Object());
            manager.switchToPage("first.fxml");
        });
        fx(() -> { assertSame(first[0],stage[0].getScene().getRoot()); manager.switchToPage("second.fxml"); });
        fx(() -> { assertSame(second[0],stage[0].getScene().getRoot()); manager.switchToPage("first.fxml"); });
        fx(() -> { assertSame(first[0],stage[0].getScene().getRoot()); stage[0].close(); });
    }
}
