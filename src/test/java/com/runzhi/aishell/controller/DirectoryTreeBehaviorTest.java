package com.runzhi.aishell.controller;

import com.runzhi.aishell.ui.DirectoryTree;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryTreeBehaviorTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch latch=new CountDownLatch(1);
        try{Platform.startup(latch::countDown);}catch(IllegalStateException started){latch.countDown();}
        assertTrue(latch.await(10,TimeUnit.SECONDS));
    }
    private void fx(Runnable action) throws Exception {
        CountDownLatch latch=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(()->{try{action.run();}catch(Throwable failure){error.set(failure);}finally{latch.countDown();}});
        assertTrue(latch.await(10,TimeUnit.SECONDS)); if(error.get()!=null)throw new AssertionError(error.get());
    }
    @Test void expansionIsLazyAndOldSessionCannotPopulateNewTree() throws Exception {
        List<Runnable> requests=new ArrayList<>(); List<String> selected=new ArrayList<>();
        AtomicReference<DirectoryTree> ref=new AtomicReference<>();
        fx(()->{
            DirectoryTree tree=new DirectoryTree(requests::add,()->path->List.of(path+"/child"),selected::add); ref.set(tree);
            tree.useContext("A",List.of("/A")); assertTrue(requests.isEmpty());
            tree.getRoot().getChildren().getFirst().setExpanded(true);
            assertEquals(1,requests.size(),"Expanding a directory must actually request its children");
            tree.useContext("B",List.of("/B"));
        });
        requests.removeFirst().run();
        fx(()->{
            var tree=ref.get(); assertEquals("/B",tree.getRoot().getChildren().getFirst().getValue());
            tree.getRoot().getChildren().getFirst().setExpanded(true);
        });
        requests.removeFirst().run();
        fx(()->{
            var tree=ref.get(); var root=tree.getRoot().getChildren().getFirst();
            assertEquals("/B/child",root.getChildren().getFirst().getValue());
            tree.getSelectionModel().select(root.getChildren().getFirst());
            assertEquals(List.of("/B/child"),selected);
            tree.reveal("/B/child"); assertEquals(1,selected.size(),"Synchronizing table success must not navigate a second time");
            tree.close();
        });
    }
}
