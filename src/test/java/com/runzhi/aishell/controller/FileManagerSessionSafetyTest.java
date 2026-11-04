package com.runzhi.aishell.controller;

import com.runzhi.aishell.ssh.*;
import com.runzhi.aishell.ssh.domain.*;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Session identity, stale-response and recovery cancellation regression tests without network I/O. */
class FileManagerSessionSafetyTest {
    @TempDir Path temp;
    FileManagerPanelController panel;
    final Map<String, List<FileInfo>> listings = new ConcurrentHashMap<>();
    final AtomicReference<CountDownLatch> slowRead = new AtomicReference<>();
    final CountDownLatch readEntered = new CountDownLatch(1);
    final CountDownLatch transferEntered = new CountDownLatch(1);
    final AtomicInteger transferCalls = new AtomicInteger();
    final AtomicReference<FileTransferService.OverwritePolicy> usedPolicy = new AtomicReference<>();
    SSHService service;
    TransferQueueStore queue;

    @BeforeAll static void fxStart() throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        try {Platform.startup(started::countDown);} catch(IllegalStateException e){started.countDown();}
        assertTrue(started.await(10,TimeUnit.SECONDS));
    }
    /** Execute controller interactions on JavaFX and rethrow assertion failures. */
    static void fx(Runnable work) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Platform.runLater(()->{try{work.run();}catch(Throwable e){error.set(e);}finally{done.countDown();}});
        assertTrue(done.await(10,TimeUnit.SECONDS));
        if(error.get()!=null)throw new AssertionError(error.get());
    }
    static Object get(Object target,String key) {
        try{Field f=FileManagerPanelController.class.getDeclaredField(key);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    static void set(Object target,String key,Object value) {
        try{Field f=FileManagerPanelController.class.getDeclaredField(key);f.setAccessible(true);f.set(target,value);}
        catch(Exception e){throw new AssertionError(e);}
    }
    static void call(Object target,String name,Class<?>[] types,Object...args) {
        try{Method m=FileManagerPanelController.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
        catch(Exception e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") ObservableList<FileInfo> files(){return (ObservableList<FileInfo>)get(panel,"remoteFiles");}
    @BeforeEach void setup() throws Exception {
        queue=new TransferQueueStore(temp.resolve("queue.json"));
        SSHConnectionManager connection=new SSHConnectionManager("localhost",22,"test","test-only",null){
            @Override public boolean isConnected(){return true;}
        };
        service=(SSHService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SSHService.class},(proxy,method,args)->{
            String tab=args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:"A";
            return switch(method.getName()) {
                case "getTabConnection" -> connection;
                case "getCurrentWorkingDirectoryForTab" -> "/same";
                case "getHostForTab" -> "host-"+tab;
                case "getUsernameForTab" -> "root";
                case "getTabConnectionParams" -> {var p=new ConnectionParams();p.setHost("host-"+tab);p.setPort(2222);p.setUsername("root");yield p;}
                case "listFilesForTab" -> {
                    if(tab.equals("A")&&slowRead.get()!=null){readEntered.countDown();slowRead.get().await(8,TimeUnit.SECONDS);}
                    yield FXCollections.observableArrayList(listings.getOrDefault(tab,List.of()));
                }
                case "uploadFilesForTab", "downloadFilesForTab" -> {
                    transferCalls.incrementAndGet();usedPolicy.set((FileTransferService.OverwritePolicy)args[4]);
                    FileTransferService.TransferControl control=(FileTransferService.TransferControl)args[5];
                    transferEntered.countDown();
                    while(!control.isCancelled()){try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
                    throw new CancellationException();
                }
                default -> null;
            };
        });
        fx(()->{
            panel=new FileManagerPanelController();
            set(panel,"pathField",new TextField("/same"));set(panel,"statusLabel",new Label());
            set(panel,"remoteSessionLabel",new Label());set(panel,"cancelTransferButton",new Button());
            set(panel,"transferQueueStore",queue);set(panel,"remoteFileTable",new TableView<FileInfo>(files()));
            panel.setPanelVisible(false);panel.setSshService(service,"A");
        });
        fx(()->{}); // Drain the empty recovery offer before adding test jobs.
    }
    @AfterEach void cleanup() throws Exception {
        if(slowRead.get()!=null)slowRead.get().countDown();
        if(panel!=null)fx(panel::dispose);
    }
    /** Wait for a result while reading observable state only on the FX thread. */
    void awaitFile(String name) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        AtomicBoolean found=new AtomicBoolean();
        while(System.nanoTime()<end){fx(()->found.set(files().size()==1&&files().getFirst().getName().equals(name)));if(found.get())return;Thread.sleep(25);}
        fail("Remote listing did not become "+name);
    }
    @Test void refreshAppliesMetadataAndRenamesWhenCountIsUnchanged() throws Exception {
        listings.put("A",List.of(new FileInfo("old.log","1",0,1L)));
        fx(()->panel.setPanelVisible(true));awaitFile("old.log");
        listings.put("A",List.of(new FileInfo("new.log","200",0,20L)));
        fx(()->call(panel,"loadRemoteFiles",new Class<?>[0]));awaitFile("new.log");
        fx(()->assertEquals("200",files().getFirst().getSize()));
    }
    @Test void samePathOnAnotherHostCannotReuseOldFilesOrSelection() throws Exception {
        listings.put("A",List.of(new FileInfo("a.log","1",0,1L)));
        listings.put("B",List.of(new FileInfo("b.log","2",0,2L)));
        fx(()->panel.setPanelVisible(true));awaitFile("a.log");
        fx(()->{
            ((TableView<?>)get(panel,"remoteFileTable")).getSelectionModel().select(0);
            panel.setPanelVisible(false);panel.setSshService(service,"B");
            assertTrue(files().isEmpty());
            assertTrue(((TableView<?>)get(panel,"remoteFileTable")).getSelectionModel().isEmpty());
            assertTrue(((Label)get(panel,"remoteSessionLabel")).getText().contains("host-B:2222"));
            panel.setPanelVisible(true);
        });awaitFile("b.log");
    }
    @Test void slowResponseFromPreviousSessionCannotOverwriteNewHost() throws Exception {
        slowRead.set(new CountDownLatch(1));
        listings.put("A",List.of(new FileInfo("stale.log","1",0,1L)));
        listings.put("B",List.of(new FileInfo("current.log","2",0,2L)));
        fx(()->panel.setPanelVisible(true));assertTrue(readEntered.await(5,TimeUnit.SECONDS));
        fx(()->panel.setSshService(service,"B"));awaitFile("current.log");slowRead.get().countDown();
        Thread.sleep(150);fx(()->assertEquals("current.log",files().getFirst().getName()));
    }
    @Test void activeTransferPreventsSilentSessionRebinding() throws Exception {
        fx(()->{
            call(panel,"beginTransfer",new Class<?>[]{int.class,long.class},1,-1L);
            panel.setSshService(service,"B");assertEquals("A",get(panel,"currentTabId"));
            assertTrue(((Label)get(panel,"remoteSessionLabel")).getText().contains("host-A"));
        });
    }
    @Test void lateCompletionCannotClearNewTransfer() throws Exception {
        fx(() -> {
            call(panel,"beginTransfer",new Class<?>[]{int.class,long.class},1,-1L);
            Object previous = get(panel,"activeTransferControl");
            call(panel,"cancelTransfer",new Class<?>[0]);
            call(panel,"beginTransfer",new Class<?>[]{int.class,long.class},1,-1L);
            Object current = get(panel,"activeTransferControl");
            call(panel,"finishTransfer",new Class<?>[]{FileTransferService.TransferControl.class},previous);
            assertSame(current,get(panel,"activeTransferControl"));
        });
    }
    @Test void invalidQueueEndpointReleasesBusyState() throws Exception {
        fx(() -> {
            call(panel,"beginTransfer",new Class<?>[]{int.class,long.class},1,-1L);
            call(panel,"enqueueTransfer",new Class<?>[]{TransferQueueStore.Direction.class,String.class,
                    int.class,String.class,List.class,String.class,String.class},
                    TransferQueueStore.Direction.UPLOAD,"host-A",0,"root",List.of("file"),"/same","SKIP");
            assertNull(get(panel,"activeTransferControl"));
            assertEquals(0,queue.recoverable("host-A","root").size());
        });
    }
    @Test void recoveryBatchCancellationStopsActiveJobAndDoesNotStartNext() throws Exception {
        var one=queue.enqueue(TransferQueueStore.Direction.UPLOAD,"host-A",2222,"root",List.of("one"),"/same","SKIP");
        var two=queue.enqueue(TransferQueueStore.Direction.UPLOAD,"host-A",2222,"root",List.of("two"),"/same","RESUME");
        fx(()->call(panel,"submitRecoveredJobs",new Class<?>[]{List.class},List.of(one,two)));
        assertTrue(transferEntered.await(5,TimeUnit.SECONDS));
        fx(()->call(panel,"cancelTransfer",new Class<?>[0]));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end&&queue.recoverable("host-A",2222,"root").getFirst().status()==TransferQueueStore.Status.RUNNING)Thread.sleep(25);
        assertEquals(1,transferCalls.get());assertEquals(FileTransferService.OverwritePolicy.SKIP,usedPolicy.get());
        assertEquals(2,queue.recoverable("host-A",2222,"root").size());
        assertEquals(TransferQueueStore.Status.CANCELLED,queue.recoverable("host-A",2222,"root").getFirst().status());
        assertEquals(TransferQueueStore.Status.PENDING,queue.recoverable("host-A",2222,"root").get(1).status());
    }
}

