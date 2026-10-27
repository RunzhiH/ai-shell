package com.runzhi.aishell.controller;

import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 钉住文件管理器本地侧的刷新与路径切换：本地表格必须真正重新列出目录内容。
 * 复现方式与线上一致——加载真实 FXML、只操作路径栏与刷新入口。
 */
class FileManagerLocalRefreshTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @Test
    void refreshLocalListReloadsTableContents() throws Exception {
        Path dir = Files.createTempDirectory("fm-refresh-parent");
        Path sub = Files.createDirectory(dir.resolve("child"));
        Files.createFile(dir.resolve("alpha.txt"));
        Files.createFile(sub.resolve("beta.txt"));

        FileManagerPanelController controller = loadPanelWithLocalPath(dir.toString());

        ObservableList<FileInfo> localFiles = getPrivate(controller, "localFiles");

        // 异步加载：轮询直到本地表格列出 alpha.txt 与 child 两个条目
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !containsName(localFiles, "alpha.txt")) {
            Thread.sleep(50);
        }
        assertEquals(2, localFiles.size(), "本地刷新未生效（应有 alpha.txt 与 child 两条），实际：" + localFiles);
        assertTrue(containsName(localFiles, "child"), "缺少 child 目录：" + localFiles);

        // 再换到子目录，验证路径输入框回车也能刷新
        runOnFx(() -> {
            TextField localPathField = getPrivate(controller, "localPathField");
            localPathField.setText(sub.toString());
            invokePrivate(controller, "navigateLocalPath");
        });

        long navDeadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < navDeadline && !containsName(localFiles, "beta.txt")) {
            Thread.sleep(50);
        }
        assertEquals(1, localFiles.size(), "子目录应只列出 beta.txt，实际：" + localFiles);
        assertEquals("beta.txt", localFiles.get(0).getName(), "子目录刷新内容错误：" + localFiles);
    }

    @Test
    void refreshLocalListFollowsTypedPathWithoutEnter() throws Exception {
        Path dirA = Files.createTempDirectory("fm-typed-a");
        Path dirB = Files.createDirectory(dirA.resolve("inner"));
        Files.createFile(dirA.resolve("one.txt"));
        Files.createFile(dirB.resolve("two.txt"));

        // 路径栏指向 dirA，刷新让列表落到 dirA
        FileManagerPanelController controller = loadPanelWithLocalPath(dirA.toString());
        ObservableList<FileInfo> localFiles = getPrivate(controller, "localFiles");
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !containsName(localFiles, "one.txt")) {
            Thread.sleep(50);
        }
        assertTrue(containsName(localFiles, "one.txt"), "初始目录未加载出 one.txt：" + localFiles);

        // 只改路径栏文本、不按回车，直接点“刷新”：应切到路径栏目录
        runOnFx(() -> {
            TextField localPathField = getPrivate(controller, "localPathField");
            localPathField.setText(dirB.toString());
            invokePrivate(controller, "refreshLocalList");
        });

        long typedDeadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < typedDeadline && !containsName(localFiles, "two.txt")) {
            Thread.sleep(50);
        }
        assertTrue(containsName(localFiles, "two.txt"), "刷新未跟进路径栏目录，实际：" + localFiles);
        Path current = getPrivate(controller, "localDirectory");
        assertEquals(dirB, current, "刷新后 localDirectory 未与路径栏同步");
    }

    /** 加载面板，把本地路径栏设为 path 并触发一次刷新，返回控制器 */
    private FileManagerPanelController loadPanelWithLocalPath(String path) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"));
        loader.setResources(com.runzhi.aishell.common.i18n.I18n.bundle());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loadDone = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Parent root = loader.load();
                assertNotNull(root, "FXML 根节点为空");
                assertNotNull(loader.getController(), "controller 未注入");
                TextField localPathField = getPrivate(loader.getController(), "localPathField");
                localPathField.setText(path);
                invokePrivate(loader.getController(), "refreshLocalList");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                loadDone.countDown();
            }
        });
        assertTrue(loadDone.await(15, TimeUnit.SECONDS), "FXML 加载超时");
        if (failure.get() != null) throw new AssertionError("加载或刷新调用抛错", failure.get());
        return loader.getController();
    }

    /** 允许在 FX 线程里抛受检异常的动作（如反射调用） */
    @FunctionalInterface
    interface FxAction {
        void run() throws Exception;
    }

    private void runOnFx(FxAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX 线程操作超时");
        if (failure.get() != null) throw new AssertionError("FX 线程操作抛错", failure.get());
    }

    private static boolean containsName(ObservableList<FileInfo> files, String name) {
        return files.stream().anyMatch(f -> f.getName().equals(name));
    }

    private static void setPrivate(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
