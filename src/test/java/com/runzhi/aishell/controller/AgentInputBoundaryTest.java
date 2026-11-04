package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentActivityBus;
import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Failed or ambiguous input must not silently consume questions or select a different model profile. */
class AgentInputBoundaryTest {
    private AgentWorkspaceController controller;
    private AgentConsoleView console;
    private final Tab tab=new Tab("input-test");
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }
    private void fx(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.runLater(() -> { try { action.run(); } catch(Throwable error) { failure.set(error); } finally { done.countDown(); } });
        assertTrue(done.await(15,TimeUnit.SECONDS)); if(failure.get()!=null) throw new AssertionError(failure.get());
    }
    private Object field(Object owner,String name) {
        try { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    private void set(String name,Object value) {
        try { Field field=AgentWorkspaceController.class.getDeclaredField(name); field.setAccessible(true); field.set(controller,value); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    @BeforeEach void create() throws Exception {
        fx(() -> {
            tab.setId("agent-input-test-" + UUID.randomUUID()); controller=new AgentWorkspaceController();
            console=new AgentConsoleView(List.of(),line->{},()->{},()->{});
            set("console",console); set("activeTabSupplier",(Supplier<Tab>)()->tab);
        });
    }
    @AfterEach void close() throws Exception { fx(controller::onWindowClosing); }
    @SuppressWarnings("unchecked") private Map<String,AgentActivityBus.Event> questions() {
        return (Map<String,AgentActivityBus.Event>)field(controller,"pendingQuestions");
    }
    private AgentActivityBus.Event question(Consumer<String> answer) {
        return new AgentActivityBus.Event(tab.getId(),AgentActivityBus.Kind.ASK,"Question",Instant.now(),List.of("first","second"),answer);
    }
    private void input(String line) {
        try { Method method=AgentWorkspaceController.class.getDeclaredMethod("handleLine",String.class); method.setAccessible(true); method.invoke(controller,line); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    @Test void slashCommandsStayLocalWhileQuestionIsPending() throws Exception {
        fx(() -> {
            AtomicInteger answered = new AtomicInteger();
            questions().put(tab.getId(), question(value -> answered.incrementAndGet()));
            input("/help");
            assertEquals(0, answered.get(), "slash command must not be sent to the model as an answer");
            assertTrue(questions().containsKey(tab.getId()), "pending question must remain answerable");
        });
    }
    @Test void oversizedNumericAnswerRemainsFreeTextAndDoesNotDisappear() throws Exception {
        fx(() -> {
            AtomicReference<String> answer=new AtomicReference<>(); questions().put(tab.getId(),question(answer::set));
            String value="9".repeat(100); input(value);
            assertEquals(value,answer.get()); assertFalse(questions().containsKey(tab.getId()));
        });
    }
    @Test void optionNumberResolvesToItsOriginalTextOnce() throws Exception {
        fx(() -> {
            AtomicReference<String> answer=new AtomicReference<>(); AtomicInteger calls=new AtomicInteger();
            questions().put(tab.getId(),question(value->{answer.set(value); calls.incrementAndGet();}));
            input("2"); assertEquals("second",answer.get()); assertEquals(1,calls.get());
            assertFalse(questions().containsKey(tab.getId()));
        });
    }
    @Test void failedSubmissionRetainsQuestionForSuccessfulRetry() throws Exception {
        fx(() -> {
            AtomicInteger calls=new AtomicInteger();
            AgentActivityBus.Event question=question(value->{ if(calls.incrementAndGet()==1) throw new IllegalStateException("simulated failure"); });
            questions().put(tab.getId(),question); input("first answer");
            assertSame(question,questions().get(tab.getId()));
            input("retry"); assertEquals(2,calls.get()); assertFalse(questions().containsKey(tab.getId()));
        });
    }
    @Test void restoringOldQuestionCannotOverwriteReplacement() throws Exception {
        fx(() -> {
            AgentActivityBus.Event replacement=question(value->{});
            questions().put(tab.getId(),question(value->{ questions().put(tab.getId(),replacement); throw new IllegalStateException("simulated failure"); }));
            input("answer"); assertSame(replacement,questions().get(tab.getId()));
        });
    }
    @Test void disposedWorkspaceIgnoresQueuedInput() throws Exception {
        fx(() -> {
            controller.onWindowClosing(); VBox transcript=(VBox)field(console,"transcript"); int before=transcript.getChildren().size();
            input("late input"); assertEquals(before,transcript.getChildren().size());
        });
    }
    @Test void closingFromAnswerCallbackDoesNotWriteLateAcknowledgement() throws Exception {
        fx(() -> {
            VBox transcript=(VBox)field(console,"transcript"); int before=transcript.getChildren().size();
            questions().put(tab.getId(),question(value->controller.onWindowClosing()));
            input("answer"); assertEquals(before,transcript.getChildren().size());
            assertTrue(questions().isEmpty());
        });
    }

    @Test void successfulAnswerIsRecordedOnceAndReplayedFromHistoryWithoutDuplicateEcho() throws Exception {
        fx(() -> {
            AtomicReference<String> answer=new AtomicReference<>();
            questions().put(tab.getId(),question(answer::set));
            VBox transcript=(VBox)field(console,"transcript");
            int before=transcript.getChildren().size();
            input("2");
            assertEquals("second",answer.get());
            assertEquals(before,transcript.getChildren().size(),
                    "the live answer must not be echoed a second time");
            try {
                Method render=AgentWorkspaceController.class.getDeclaredMethod("render",Tab.class);
                render.setAccessible(true); render.invoke(controller,tab);
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertEquals(1,transcript.lookupAll(".agent-message-user").size());
            StringBuilder replay=new StringBuilder(); collectText(transcript,replay);
            assertTrue(replay.toString().contains("2"));
            assertFalse(replay.toString().contains("second"),
                    "history must preserve the user's original input, not the resolved option value");
        });
    }

    @Test void submitEnterKeepsOriginalInputOnceWhileCallbackGetsResolvedOption() throws Exception {
        fx(() -> {
            AtomicReference<String> answer=new AtomicReference<>();
            questions().put(tab.getId(),question(answer::set));
            AgentConsoleView submitConsole=new AgentConsoleView(List.of(),line->{
                try {
                    Method handle=AgentWorkspaceController.class.getDeclaredMethod("handleLine",String.class);
                    handle.setAccessible(true); handle.invoke(controller,line);
                } catch (Exception failure) { throw new AssertionError(failure); }
            },()->{},()->{});
            set("console",submitConsole);
            TextArea input=(TextArea)field(submitConsole,"input");
            input.setText("2");
            input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));
            assertEquals("second",answer.get());

            VBox transcript=(VBox)field(submitConsole,"transcript");
            assertEquals(1,transcript.lookupAll(".agent-message-user").size());
            String live=collectText(transcript);
            assertTrue(live.contains("2"));
            assertFalse(live.contains("second"));

            try {
                Method render=AgentWorkspaceController.class.getDeclaredMethod("render",Tab.class);
                render.setAccessible(true); render.invoke(controller,tab);
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertEquals(1,transcript.lookupAll(".agent-message-user").size());
            String replay=collectText(transcript);
            assertTrue(replay.contains("2"));
            assertFalse(replay.contains("second"));
        });
    }

    private static String collectText(Node node) {
        StringBuilder text=new StringBuilder();
        collectText(node,text);
        return text.toString();
    }

    private static void collectText(Node node, StringBuilder text) {
        if (node instanceof Text value) text.append(value.getText()).append('\n');
        else if (node instanceof Label label) text.append(label.getText()).append('\n');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectText(child, text);
        }
    }
    @Test void duplicateModelAliasRequiresExplicitChoice() {
        AIConfig one=new AIConfig("first","OpenAI","dummy","http://localhost","shared");
        AIConfig two=new AIConfig("second","OpenAI","dummy","http://localhost","shared");
        assertThrows(IllegalArgumentException.class,()->AgentWorkspaceController.findModel("shared",List.of(one,two)));
        assertSame(two,AgentWorkspaceController.findModel("2",List.of(one,two)));
    }
    @Test void profileNamePrecedesModelAliasAndMissingModelsAreSafe() {
        AIConfig alias=new AIConfig("first","OpenAI","dummy","http://localhost","shared");
        AIConfig named=new AIConfig("shared","OpenAI","dummy","http://localhost",null);
        assertSame(named,AgentWorkspaceController.findModel("shared",List.of(alias,named)));
        assertNull(AgentWorkspaceController.findModel("missing",List.of(alias,named)));
        assertNull(AgentWorkspaceController.findModel("999",List.of(alias,named)));
    }
}
