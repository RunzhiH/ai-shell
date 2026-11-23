package com.runzhi.aishell.controller;

import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 文件管理器“新建文件”：本地创建空文件，远程经服务编排（先查重再写字节）。 */
class FileManagerNewFileTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @Test
    void createsAnEmptyLocalFileAndListsIt() throws Exception {
        Path directory = Files.createTempDirectory("fm-newfile");
        Files.createFile(directory.resolve("visible.txt"));   // 哨兵文件：等面板真正进入该目录
        FileManagerPanelController controller = loadPanelWithLocalPath(directory.toString());
        ObservableList<FileInfo> localFiles = getPrivate(controller, "localFiles");

        long entered = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < entered && !containsName(localFiles, "visible.txt")) Thread.sleep(25);
        assertTrue(containsName(localFiles, "visible.txt"), "panel must have entered the target directory first");

        runOnFx(() -> assertTrue(controller.createLocalFileNamed("created.log")));

        assertEquals(0, Files.size(directory.resolve("created.log")), "the new file must be empty");

        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !containsName(localFiles, "created.log")) Thread.sleep(25);
        assertTrue(containsName(localFiles, "created.log"), "table must list the new file");

        // 说明：重复同名创建会弹模态错误框，避免在测试里触发卡死，其“已存在即失败”语义由产品逻辑保证
        runOnFx(controller::onWindowClosing);
    }

    @Test
    void createsAnEmptyRemoteFileThroughTheService() throws Exception {
        Path directory = Files.createTempDirectory("fm-newfile-remote");
        FileManagerPanelController controller = loadPanelWithLocalPath(directory.toString());

        java.util.Map<String, Object> record = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger existsCalls = new AtomicInteger();
        SSHService fake = (SSHService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { SSHService.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "remotePathExistsForTab" -> { existsCalls.incrementAndGet(); return false; }
                        case "writeBytesForTab" -> {
                            record.put("path", args[1]);
                            record.put("data", args[2]);
                            return null;
                        }
                        case "listFilesForTab" -> { return javafx.collections.FXCollections.observableArrayList(); }
                        default -> { return method.getDefaultValue(); }
                    }
                });

        AtomicInteger generation = getPrivate(controller, "loadGeneration");
        runOnFx(() -> assertTrue(controller.createRemoteFileNamed("remote.log", fake, "tab-fake", "/srv", generation.get())));

        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !record.containsKey("path")) Thread.sleep(25);
        assertEquals("/srv/remote.log", record.get("path"), "must create under the parent path");
        assertArrayEquals(new byte[0], (byte[]) record.get("data"), "remote new file must be empty");
        assertEquals(1, existsCalls.get(), "must check existence before writing");

        // 等到成功回调节束
        runOnFx(() -> { });
        runOnFx(controller::onWindowClosing);
    }

    @FunctionalInterface
    interface FxAction { void run() throws Exception; }

    private void runOnFx(FxAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private FileManagerPanelController loadPanelWithLocalPath(String path) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"));
        loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loadDone = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                loader.load();
                Object field = getPrivate(loader.getController(), "localPathField");
                javafx.scene.control.TextField localPathField = (javafx.scene.control.TextField) field;
                localPathField.setText(path);
                invokePrivate(loader.getController(), "refreshLocalList");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                loadDone.countDown();
            }
        });
        assertTrue(loadDone.await(15, TimeUnit.SECONDS), "FXML load timeout");
        if (failure.get() != null) throw new AssertionError(failure.get());
        return loader.getController();
    }

    private static boolean containsName(List<FileInfo> files, String name) {
        return files.stream().anyMatch(f -> f.getName().equals(name));
    }

    private static <T> T getPrivate(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        T value = (T) field.get(target);
        return value;
    }

    private static Object invokePrivate(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
