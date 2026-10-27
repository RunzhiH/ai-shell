package com.runzhi.aishell.terminal;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TerminalInputBinderTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void bindsEnterButtonsAndAiModePresentation() throws Exception {
        AtomicInteger submits = new AtomicInteger();
        AtomicInteger reconnects = new AtomicInteger();
        runOnFx(() -> {
            TextField input = new TextField();
            ChoiceBox<String> mode = new ChoiceBox<>();
            Button execute = new Button();
            Button reconnect = new Button();
            TerminalInputBinder.bind(input, mode, execute, reconnect,
                    submits::incrementAndGet, reconnects::incrementAndGet);

            input.getOnAction().handle(new javafx.event.ActionEvent());
            execute.fire();
            reconnect.fire();
            mode.setValue("AI模式");

            assertEquals("提交给 AI", execute.getText());
            assertTrue(input.getPromptText().contains("AI"));
            assertTrue(input.getStyle().contains("#388bfd"));
        });
        assertEquals(2, submits.get());
        assertEquals(1, reconnects.get());
    }

    private void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable e) { failure.set(e); } finally { done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
