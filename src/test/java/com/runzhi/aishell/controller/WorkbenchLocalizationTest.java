package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.config.ShortcutConfigStore;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Translation must preserve shortcut identities and usable narrow-screen actions. */
class WorkbenchLocalizationTest {
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    private void fx(Runnable work) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.runLater(() -> { try { work.run(); } catch(Throwable t) { failure.set(t); } finally { done.countDown(); } });
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(failure.get()!=null) throw new AssertionError(failure.get());
    }
    @Test void translatedActionLabelsKeepExistingPersistenceKeys() throws Exception {
        fx(() -> {
            Locale original=I18n.current();
            try {
                I18n.setLocale(Locale.ENGLISH,false);
                var action=new SettingsShortcutsController.ShortcutInfo("终端","新建终端标签","Ctrl+T",false);
                assertEquals("New terminal tab",action.getFunctionLabel());
                assertEquals("Terminal",action.getCategory());
                assertEquals("新建终端标签",action.getFunction());
                assertTrue(ShortcutConfigStore.defaults().containsKey(action.getFunction()));
                I18n.setLocale(Locale.SIMPLIFIED_CHINESE,false);
                assertEquals("新建终端标签",action.getFunctionLabel());
            } finally { I18n.setLocale(original,false); }
        });
    }
    @Test void englishToolbarRemainsInsideWindowWithLongHostName() throws Exception {
        fx(() -> {
            Locale original=I18n.current();
            try {
                I18n.setLocale(Locale.ENGLISH,false);
                for(int width:new int[]{1280,1600}) {
                    FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/main-view.fxml"));
                    loader.setResources(I18n.bundle());
                    loader.setControllerFactory(type -> new MainViewController() {
                        @Override public void initialize(URL location,ResourceBundle resources) { }
                    });
                    Parent root=loader.load(); Scene scene=new Scene(root,width,800);
                    ((Label)loader.getNamespace().get("currentHostLabel")).setText("root@production-application-server-long-name.example.internal:2222");
                    scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/light-theme.css").toExternalForm());
                    root.resize(width,800); root.applyCss(); root.layout();
                    HBox toolbar=(HBox)root.lookup(".topbar");
                    for(var node:toolbar.getChildren()) {
                        assertTrue(node.localToScene(node.getBoundsInLocal()).getMaxX()<=width+1,
                                "Toolbar overflow at "+width+": "+node);
                        if(node instanceof Button button) assertTrue(button.getWidth()>=button.prefWidth(-1)-1,
                                "Truncated action at "+width+": "+button.getText());
                    }
                    for(var node:toolbar.lookupAll(".button")) {
                        Button action=(Button)node;
                        assertTrue(action.getWidth()>=action.prefWidth(-1)-1,"Truncated navigation: "+action.getText());
                    }
                    assertEquals("Connections",((Button)loader.getNamespace().get("navConnections")).getText());
                }
            } catch(Exception failure) { throw new AssertionError(failure); }
            finally { I18n.setLocale(original,false); }
        });
    }
}
