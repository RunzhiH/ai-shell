package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileManagerCompactLayoutTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try{Platform.startup(ready::countDown);}catch(IllegalStateException started){ready.countDown();}
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    @Test void bothModesReserveMostDrawerSpaceForFilesInBothThemes() throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(()->{
            try {
                for(String theme:new String[]{"style.css","light-theme.css"}) for(int width:new int[]{600,960}) {
                    FXMLLoader loader=new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"),I18n.bundle());
                    Parent root=loader.load(); var controller=(FileManagerPanelController)loader.getController();
                    try {
                        Scene scene=new Scene(root,width,300);
                        scene.getStylesheets().add(getClass().getResource("/com/runzhi/aishell/"+theme).toExternalForm());
                        TabPane modes=(TabPane)loader.getNamespace().get("fileModes");
                        for(String mode:new String[]{"remote","local"}) {
                            modes.getSelectionModel().select((Tab)loader.getNamespace().get(mode+"BrowserTab"));
                            root.resize(width,300); root.applyCss(); root.layout(); root.layout();
                            Region tree=(Region)loader.getNamespace().get(mode+"TreeHost");
                            TableView<?> files=(TableView<?>)loader.getNamespace().get(mode+"FileTable");
                            assertTrue(tree.getWidth()<=175,"Navigator expanded beyond compact default: "+tree.getWidth());
                            assertTrue(files.getWidth()>width*0.65,"Table should own most horizontal space: "+files.getWidth());
                            assertTrue(files.getHeight()>=125,"Headers leave too few file rows: "+files.getHeight());
                            SplitPane split=(SplitPane)loader.getNamespace().get(mode+"FileSplit");
                            assertEquals(Boolean.FALSE,SplitPane.isResizableWithParent(tree));
                            split.setDividerPositions(210.0/split.getWidth()); root.layout(); split.layout();
                            assertTrue(tree.getWidth()>175,"Divider must remain adjustable");
                        }
                        assertFalse(((Label)loader.getNamespace().get("remoteSessionLabel")).isManaged());
                    } finally {controller.onWindowClosing();}
                }
            } catch(Throwable failure){error.set(failure);} finally{done.countDown();}
        });
        assertTrue(done.await(30,TimeUnit.SECONDS)); if(error.get()!=null)throw new AssertionError(error.get());
    }
}
