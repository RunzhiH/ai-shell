package com.runzhi.aishell.controller;

import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件管理器“显示隐藏文件”开关：本地按平台语义、远程按点号开头，
 * 开关状态持久化并作用于文件表与目录树。
 */
class FileManagerHiddenFilesTest {
    private static final Preferences PREFS = Preferences.userNodeForPackage(FileManagerPanelController.class);
    private static final String SHOW_HIDDEN_KEY = "fileManager.showHidden";

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @Test
    void remoteDotEntriesAreHiddenWhenTheToggleIsOff() {
        assertTrue(FileManagerPanelController.isHiddenRemoteName(".bashrc"));
        assertTrue(FileManagerPanelController.isHiddenRemoteName(".ssh"));
        assertFalse(FileManagerPanelController.isHiddenRemoteName("visible.txt"));
        assertFalse(FileManagerPanelController.isHiddenRemoteName(""));
        assertFalse(FileManagerPanelController.isHiddenRemoteName(null));
    }

    @Test
    void togglingFiltersPlatformHiddenLocalEntriesAndPersists() throws Exception {
        // 强制已知初始状态并兜底恢复，避免先前失败运行残留的偏好污染本用例
        String savedPref = PREFS.get(SHOW_HIDDEN_KEY, null);
        PREFS.putBoolean(SHOW_HIDDEN_KEY, true);
        try {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path directory = Files.createTempDirectory("fm-hidden");
        Files.createFile(directory.resolve("visible.txt"));
        Path dotFile = Files.createFile(directory.resolve(".dotfile"));
        Path platformHidden = null;
        if (windows) {
            platformHidden = Files.createFile(directory.resolve("secret.txt"));
            Files.setAttribute(platformHidden, "dos:hidden", true);
            assertTrue(Files.isHidden(platformHidden), "Windows hidden attribute must be set for the test");
        }

        FileManagerPanelController controller = loadPanelWithLocalPath(directory.toString());
        ObservableList<FileInfo> localFiles = getPrivate(controller, "localFiles");

        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !containsName(localFiles, "visible.txt")) Thread.sleep(25);
        assertTrue(containsName(localFiles, "visible.txt"), "visible file must be listed");

        // 默认显示隐藏文件（与既有无过滤行为一致）
        assertTrue(PREFS.getBoolean(SHOW_HIDDEN_KEY, true));
        assertTrue(containsName(localFiles, ".dotfile"));
        if (windows) assertTrue(containsName(localFiles, "secret.txt"));

        // 关闭开关：平台隐藏项消失；dotfile 按所在平台语义处理
        ToggleButton toggle = findToggle(controller);
        runOnFx(() -> toggle.fire());
        boolean hiddenAfterToggle = getPrivate(controller, "showHidden");
        assertFalse(hiddenAfterToggle);
        assertFalse(PREFS.getBoolean(SHOW_HIDDEN_KEY, true), "toggle must persist off state");
        if (windows) {
            deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline && containsName(localFiles, "secret.txt")) Thread.sleep(25);
            assertFalse(containsName(localFiles, "secret.txt"), "platform hidden entry must be filtered");
        }

        // 重新打开：恢复显示，新面板从偏好恢复
        runOnFx(() -> toggle.fire());
        boolean hiddenAfterReenable = getPrivate(controller, "showHidden");
        assertTrue(hiddenAfterReenable);
        deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !containsName(localFiles, "visible.txt")) Thread.sleep(25);
        if (windows) {
            deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline && !containsName(localFiles, "secret.txt")) Thread.sleep(25);
            assertTrue(containsName(localFiles, "secret.txt"), "hidden entry must reappear after enabling");
        }
        controller.onWindowClosing();
        } finally {
            if (savedPref == null) PREFS.remove(SHOW_HIDDEN_KEY);
            else PREFS.put(SHOW_HIDDEN_KEY, savedPref);
        }
    }

    private ToggleButton findToggle(FileManagerPanelController controller) throws Exception {
        Object toolbar = getPrivate(controller, "localFileToolbar");
        assertNotNull(toolbar);
        for (Node child : ((javafx.scene.layout.FlowPane) toolbar).getChildren()) {
            if (child instanceof ToggleButton toggle && toggle.getStyleClass().contains("secondary-button")) return toggle;
        }
        throw new AssertionError("show-hidden toggle not found in local toolbar");
    }

    private FileManagerPanelController loadPanelWithLocalPath(String path) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"));
        loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loadDone = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                loader.load();
                assertNotNull(loader.getController());
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
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
