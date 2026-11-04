package com.runzhi.aishell.controller;

import com.runzhi.aishell.ai.AIConfig;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ui.AgentConsoleView;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** English presentation must retain narrow-screen actions and the original high-risk gate. */
class AgentPresentationTest {
    @Test void expiredAndInvalidCardsExposeDifferentActions() throws Exception {
        englishFx(() -> {
            var context=new com.runzhi.aishell.agent.AgentProposal.Context("task-3","root","staging:22","/srv","restart service");
            var expired=new com.runzhi.aishell.agent.AgentProposal("C",context,java.time.Instant.now().minusSeconds(1),()->true,_ -> {});
            var invalid=new com.runzhi.aishell.agent.AgentProposal("C",context,java.time.Instant.now().plusSeconds(60),()->false,_ -> {});
            for(var proposal:List.of(expired,invalid)) {
                AgentConsoleView view=new AgentConsoleView(List.of(),_ -> {},()->{},()->{});
                view.printCommandProposal("command","中风险",proposal,proposal::decide,_ -> fail("Invalid action must not insert"),()->{});
                var scene=new Scene(view,352,800); view.applyCss(); view.layout();
                var card=view.lookup(".ai-command-card");
                var buttons=card.lookupAll(".button").stream().map(n->(Button)n).toList();
                assertTrue(buttons.stream().filter(b->b.getText().equals(I18n.tr("agent.execute")) || b.getText().equals(I18n.tr("agent.reject"))).allMatch(Button::isDisabled));
                Button send=buttons.stream().filter(b->b.getText().equals(I18n.tr("agent.send"))).findFirst().orElseThrow();
                assertEquals(proposal==invalid,send.isDisabled());
                assertTrue(buttons.stream().anyMatch(b->b.getText().equals(I18n.tr(proposal==invalid?"agent.approval.newTask":"agent.approval.reapply")) && b.isVisible()));
                // Release the card timeline just as leaving the workspace does.
                scene.setRoot(new javafx.scene.layout.VBox());
            }
        });
    }

    @Test void workspaceFollowsSelectedTerminalWhileStopTargetsDisplayedSession() throws Exception {
        englishFx(() -> {
            var controller=new AgentWorkspaceController();
            var c=new javafx.scene.control.Tab("C"); c.setId("phase4-target-C");
            var b=new javafx.scene.control.Tab("B"); b.setId("phase4-active-B");
            var active=new AtomicReference<>(c); var executed=new AtomicReference<javafx.scene.control.Tab>();
            var stopped=new AtomicReference<String>();
            var commands=(com.runzhi.aishell.service.TerminalCommandService)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class[]{com.runzhi.aishell.service.TerminalCommandService.class},(_,method,args)->{
                        if(method.getName().equals("processAITerminalCommand")) executed.set((javafx.scene.control.Tab)args[0]); return null;
                    });
            var tracker=com.runzhi.aishell.agent.AgentSessionTracker.getInstance();
            try {
                controller.configure(active::get,commands,new AgentWorkspaceBridge((_,_)->false,stopped::set));
                var input=AgentWorkspaceController.class.getDeclaredMethod("handleLine",String.class); input.setAccessible(true);
                input.invoke(controller,"inspect service");
                assertSame(c,executed.get());
                tracker.markRunning(c.getId()); tracker.markRunning(b.getId());
                active.set(b); controller.showTab(b); controller.stopAgent();
                assertEquals(b.getId(),stopped.get(),"切到 B 后停止应作用于 B，而不是被钉住的 C");
                assertTrue(tracker.isRunning(c.getId()),"C 的 Agent 任务不得因切换终端而被停止");
            } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
            finally {controller.onWindowClosing(); tracker.clear(c.getId()); tracker.clear(b.getId());}
        });
    }
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }
    private void englishFx(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(() -> {
            Locale original=I18n.current();
            try { I18n.setLocale(Locale.ENGLISH,false); action.run(); }
            catch(Throwable failure) { error.set(failure); }
            finally { I18n.setLocale(original,false); done.countDown(); }
        });
        assertTrue(done.await(20,TimeUnit.SECONDS)); if(error.get()!=null) throw new AssertionError(error.get());
    }
    @Test void longCommandKeepsAllFourActionsInside352PixelWorkspace() throws Exception {
        englishFx(() -> {
            AgentConsoleView view=new AgentConsoleView(List.of(),line->{},()->{},()->{});
            Scene scene=new Scene(view,352,800);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/light-theme.css").toExternalForm());
            view.printCommandProposal("cat /var/log/"+"long-directory-name/".repeat(18)+"app.log","高风险",decision->{},command->{});
            view.resize(352,800); view.applyCss(); view.layout();
            var card=view.lookup(".ai-command-card"); assertNotNull(card);
            var actions=card.lookupAll(".button"); assertEquals(4,actions.size());
            for(var node:actions) {
                Button button=(Button)node;
                assertTrue(button.getWidth()>=button.prefWidth(-1)-1,"Truncated "+button.getText());
                assertTrue(button.localToScene(button.getBoundsInLocal()).getMaxX()<=353,"Outside workspace: "+button.getText());
            }
            assertTrue(((Label)view.lookup(".ai-command-title")).getText().contains("High risk"));
        });
    }
    @Test void englishHighRiskActionStillRequiresConfirmation() throws Exception {
        englishFx(() -> {
            AgentConsoleView view=new AgentConsoleView(List.of(),line->{},()->{},()->{});
            AtomicBoolean dialogSeen=new AtomicBoolean(); AtomicBoolean approved=new AtomicBoolean();
            view.printCommandProposal("test-command","高风险",approved::set,command->{});
            Scene scene=new Scene(view,352,800);
            scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/light-theme.css").toExternalForm());
            view.applyCss(); view.layout();
            Button execute=(Button)view.lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && button.getText().equals("Execute")).findFirst().orElseThrow();
            Platform.runLater(() -> {
                for(Window window:List.copyOf(Window.getWindows())) {
                    if(window.getScene()!=null && window.getScene().getRoot() instanceof DialogPane dialog) {
                        dialogSeen.set(true); ((Button)dialog.lookupButton(ButtonType.CANCEL)).fire();
                    }
                }
            });
            execute.fire();
            assertTrue(dialogSeen.get(),"Translated risk must not bypass confirmation");
            assertFalse(approved.get());
        });
    }
    @Test @SuppressWarnings("unchecked") void longModelProfilesKeepRefreshVisibleAndTooltipDoesNotExposeCredentials() throws Exception {
        englishFx(() -> {
            try {
                FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/ai-assistant-panel.fxml"));
                loader.setResources(I18n.bundle());
                loader.setControllerFactory(type -> new AIAssistantPanelController() {
                    @Override public void initialize(URL location,ResourceBundle resources) { }
                });
                Parent root=loader.load(); AIAssistantPanelController controller=loader.getController();
                AIConfig profile=new AIConfig("long-profile-".repeat(10),"OpenAI","dummy-private-key","http://localhost","long-model-".repeat(10));
                controller.populateModels(List.of(profile),profile);
                Scene scene=new Scene(root,640,480);
                scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/light-theme.css").toExternalForm());
                root.resize(640,480); root.applyCss(); root.layout();
                Button refresh=(Button)loader.getNamespace().get("refreshModelsButton");
                assertEquals("Refresh",refresh.getText());
                assertTrue(refresh.localToScene(refresh.getBoundsInLocal()).getMaxX()<=641);
                assertTrue(refresh.getWidth()>=refresh.prefWidth(-1)-1);
                ChoiceBox<AIConfig> models=(ChoiceBox<AIConfig>)loader.getNamespace().get("modelChoice");
                assertTrue(models.getTooltip().getText().contains(profile.getModel()));
                assertFalse(models.getTooltip().getText().contains("dummy-private-key"));
                controller.onWindowClosing();
            } catch(Exception failure) { throw new AssertionError(failure); }
        });
    }
}
