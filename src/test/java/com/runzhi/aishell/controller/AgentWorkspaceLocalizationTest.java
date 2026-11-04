package com.runzhi.aishell.controller;

import com.runzhi.aishell.agent.AgentSessionTracker;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Translated Agent presentation must preserve task and command identities. */
class AgentWorkspaceLocalizationTest {
    private AgentWorkspaceController controller; private Tab tab;
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }
    private void fx(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.runLater(() -> {
            Locale original=I18n.current();
            try { I18n.setLocale(Locale.ENGLISH,false); action.run(); }
            catch(Throwable error) { failure.set(error); }
            finally { I18n.setLocale(original,false); done.countDown(); }
        });
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(failure.get()!=null) throw new AssertionError(failure.get());
    }
    private Object call(String name,Class<?>[] types,Object... args) {
        try { Method method=AgentWorkspaceController.class.getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(controller,args); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    private Object field(Object owner,String name) {
        try { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    @BeforeEach void load() throws Exception {
        fx(() -> { try {
            FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/agent-workspace.fxml"));
            loader.load(); controller=loader.getController();
            tab=new Tab("test-session"); tab.setId("agent-locale-test-" + UUID.randomUUID());
            controller.configure(() -> tab,null,null);
        } catch(Exception failure) { throw new AssertionError(failure); } });
    }
    @AfterEach void dispose() throws Exception { fx(() -> { controller.onWindowClosing(); AgentSessionTracker.getInstance().clear(tab.getId()); }); }
    @Test @SuppressWarnings("unchecked") void catalogAndHelpTranslateWithoutChangingSlashCommandNames() throws Exception {
        fx(() -> {
            List<AgentConsoleView.Command> commands=(List<AgentConsoleView.Command>)call("commands",new Class<?>[0]);
            assertEquals(List.of("/help","/new","/tasks","/resume","/continue","/rename","/delete","/clear-history","/model","/context","/permissions","/auto","/stop","/clear"),
                    commands.stream().map(AgentConsoleView.Command::name).toList());
            String help=(String)call("commandHelp",new Class<?>[0]);
            for(var command:commands) assertTrue(help.contains(command.name()) && help.contains(command.description()));
            assertEquals("Show all commands",commands.getFirst().description());
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE,false);
            List<AgentConsoleView.Command> chinese=(List<AgentConsoleView.Command>)call("commands",new Class<?>[0]);
            assertEquals("显示全部命令",chinese.getFirst().description());
            assertEquals(commands.stream().map(AgentConsoleView.Command::name).toList(),chinese.stream().map(AgentConsoleView.Command::name).toList());
        });
    }
    @Test void generatedTitlesTranslateButTaskIdsAndUserTitlesStayStable() throws Exception {
        fx(() -> {
            Object first=tab.getProperties().get("agentTaskId");
            assertTrue(((String)call("taskList",new Class<?>[]{Tab.class},tab)).contains("Task 1"));
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE,false);
            assertTrue(((String)call("taskList",new Class<?>[]{Tab.class},tab)).contains("任务 1"));
            assertEquals(first,tab.getProperties().get("agentTaskId"));
            call("handleCommand",new Class<?>[]{String.class},"/new 自定义 incident task");
            Object named=tab.getProperties().get("agentTaskId"); assertNotEquals(first,named);
            I18n.setLocale(Locale.ENGLISH,false);
            assertTrue(((String)call("taskList",new Class<?>[]{Tab.class},tab)).contains("自定义 incident task"));
            call("handleCommand",new Class<?>[]{String.class},"/resume 1");
            assertEquals(first,tab.getProperties().get("agentTaskId"));
        });
    }
    @Test void contextAndPermissionsReflectExistingStateInEnglish() throws Exception {
        fx(() -> {
            String context=(String)call("contextSummary",new Class<?>[]{Tab.class},tab);
            assertTrue(context.contains("Connection") && context.contains("Task 1") && context.contains("History"));
            tab.getProperties().put("agentAutoApproveLowRisk",true);
            String permissions=(String)call("permissionSummary",new Class<?>[]{Tab.class},tab);
            assertTrue(permissions.contains("automatically approved") && permissions.contains("additional confirmation"));
            assertEquals(Boolean.TRUE,tab.getProperties().get("agentAutoApproveLowRisk"));
        });
    }
    @Test void runningHintNamesSupportedEscapeAndKeepsTaskSelection() throws Exception {
        fx(() -> {
            Object id=tab.getProperties().get("agentTaskId");
            AgentSessionTracker.getInstance().markRunning(tab.getId());
            call("handleLine",new Class<?>[]{String.class},"show logs");
            call("handleCommand",new Class<?>[]{String.class},"/new another task");
            AgentConsoleView console=(AgentConsoleView)field(controller,"console");
            VBox transcript=(VBox)field(console,"transcript");
            StringBuilder text=new StringBuilder();
            collectText(transcript,text);
            assertTrue(text.toString().contains("Esc") && text.toString().contains("/stop"));
            assertEquals(id,tab.getProperties().get("agentTaskId"));
        });
    }

    private static void collectText(Node node, StringBuilder text) {
        if (node instanceof Text value) text.append(value.getText()).append('\n');
        else if (node instanceof Label label) text.append(label.getText()).append('\n');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectText(child, text);
        }
    }
}
