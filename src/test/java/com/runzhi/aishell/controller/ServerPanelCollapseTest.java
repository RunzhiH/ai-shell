package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.*;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import static org.junit.jupiter.api.Assertions.*;

class ServerPanelCollapseTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch(IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    @Test void sidebarButtonReleasesWidthAndToolbarRestoresSamePanelRepeatedly() throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.runLater(()->{
            Preferences prefs=Preferences.userNodeForPackage(MainViewController.class);
            String open=prefs.get("layout.server.open",null), divider=prefs.get("layout.server.divider",null);
            try {
                for(int width:new int[]{1280,1600}) {
                    FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/main-view.fxml"),I18n.bundle());
                    loader.setControllerFactory(type->new MainViewController(){@Override public void initialize(URL url,ResourceBundle resources) { }});
                    Parent root=loader.load(); new Scene(root,width,800);
                    SplitPane split=(SplitPane)loader.getNamespace().get("mainSplitPane");
                    Region content=(Region)loader.getNamespace().get("contentArea");
                    var sidebar=loader.getNamespace().get("serverPanel");
                    Button collapse=(Button)loader.getNamespace().get("btnCollapseServer");
                    Button toggle=(Button)loader.getNamespace().get("btnToggleServer");
                    root.resize(width,800); root.applyCss(); root.layout();
                    for(int cycle=0;cycle<3;cycle++) {
                        collapse.fire(); root.layout(); split.layout();
                        assertEquals(1,split.getItems().size()); assertTrue(split.getDividers().isEmpty());
                        assertTrue(content.localToScene(0,0).getX()-split.localToScene(0,0).getX()<4,"Collapsed sidebar left a blank slot");
                        assertTrue(content.getWidth()>=split.getWidth()-4,"Terminal did not reclaim sidebar width");
                        toggle.fire(); root.layout(); split.layout();
                        assertEquals(2,split.getItems().size()); assertSame(sidebar,split.getItems().getFirst());
                        assertTrue(content.localToScene(0,0).getX()-split.localToScene(0,0).getX()>=220,"Reopened sidebar did not reserve its own width");
                    }
                }
            } catch(Throwable error) { failure.set(error); }
            finally {
                Platform.runLater(()->{
                if(open==null)prefs.remove("layout.server.open");else prefs.put("layout.server.open",open);
                if(divider==null)prefs.remove("layout.server.divider");else prefs.put("layout.server.divider",divider);
                done.countDown();
                });
            }
        });
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(failure.get()!=null)throw new AssertionError(failure.get());
    }
}
