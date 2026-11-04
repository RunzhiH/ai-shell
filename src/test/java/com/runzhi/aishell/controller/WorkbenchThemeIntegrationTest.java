package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Real workbench FXML and skins, with service startup excluded to keep rendering deterministic. */
class WorkbenchThemeIntegrationTest {
    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    @Test void fullWorkbenchResolvesPaletteAcrossThemeChangesWithoutCssWarnings() throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.runLater(() -> {
            Logger css=Logger.getLogger("javafx.css"); List<String> warnings=new ArrayList<>();
            Handler capture=new Handler() {
                @Override public void publish(LogRecord record) { if(record.getLevel().intValue()>=Level.WARNING.intValue()) warnings.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            css.addHandler(capture);
            try {
                FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/main-view.fxml"));
                loader.setResources(I18n.bundle());
                loader.setControllerFactory(type -> new MainViewController() {
                    @Override public void initialize(URL location,ResourceBundle resources) { }
                });
                Parent root=loader.load(); Scene scene=new Scene(root,1280,800);
                TextField filter=(TextField)loader.getNamespace().get("serverTreeFilter");
                filter.setText("prod-web"); filter.selectAll();
                for(String theme:List.of("style.css","light-theme.css","style.css")) {
                    scene.getStylesheets().setAll(getClass().getResource("/com/runzhi/aishell/"+theme).toExternalForm());
                    root.applyCss(); root.layout(); root.applyCss();
                    Color expected=Color.web(theme.equals("light-theme.css")?"#FFFFFF":"#2D2D2D");
                    assertEquals(expected,((Region)filter).getBackground().getFills().getFirst().getFill(),theme+" filter surface");
                    assertTrue(root.getStyleClass().contains("root"),"Scene root must expose palette tokens");
                    assertEquals("prod-web",filter.getText());
                }
                assertTrue(warnings.isEmpty(),"Workbench CSS warnings: "+warnings);
            } catch(Throwable error) { failure.set(error); }
            finally { css.removeHandler(capture); done.countDown(); }
        });
        assertTrue(done.await(30,TimeUnit.SECONDS));
        if(failure.get()!=null) throw new AssertionError(failure.get());
    }
}
