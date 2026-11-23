package com.runzhi.aishell.editor;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TabPane;
import com.runzhi.aishell.editor.EditorText;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑器窗口单测（方案 §9）：FXML 加载、脏标记、保存按钮状态、保存回写与标签复用。
 * 冲突对话框分支由 RemoteEditorServiceTest 覆盖，UI 测试不弹真实对话框。
 */
class EditorWindowTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void localFileOpensEditsAndSavesBackWithUtf8AndEolPreserved() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path file = dir.resolve("notes.txt");
        Files.write(file, "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8));

        EditorWindowController controller = loadController();
        try {
            fx(() -> controller.openLocal(file.toString()));
            EditorText area = awaitEditor(controller, 0);
            assertEquals("alpha\nbeta\n", area.getText());

            fx(() -> area.appendText("gamma\n"));
            awaitCondition(() -> controllerDirtyOf(controller));
            assertTrue(controllerDirtyOf(controller), "编辑后应标记为脏");

            fx(controller::onSave);
            awaitCondition(() -> !controllerDirtyOf(controller));
            assertEquals("alpha\nbeta\ngamma\n",
                    Files.readString(file, StandardCharsets.UTF_8));
        } finally {
            controller.onWindowClosing();
        }
    }

    @Test
    void openingTheSameFileTwiceReusesOneTab() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path file = dir.resolve("dup.txt");
        Files.write(file, "one".getBytes(StandardCharsets.UTF_8));

        EditorWindowController controller = loadController();
        try {
            fx(() -> { controller.openLocal(file.toString()); controller.openLocal(file.toString()); });
            awaitCondition(() -> tabPane(controller).getTabs().size() == 1);
            assertEquals(1, tabPane(controller).getTabs().size());
        } finally {
            controller.onWindowClosing();
        }
    }

    @Test
    void multipleFilesOpenAsSeparateTabs() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path a = dir.resolve("a.txt");
        Path b = dir.resolve("b.txt");
        Files.write(a, "a".getBytes(StandardCharsets.UTF_8));
        Files.write(b, "b".getBytes(StandardCharsets.UTF_8));

        EditorWindowController controller = loadController();
        try {
            fx(() -> { controller.openLocal(a.toString()); controller.openLocal(b.toString()); });
            awaitCondition(() -> tabPane(controller).getTabs().size() == 2);
        } finally {
            controller.onWindowClosing();
        }
    }

    @Test
    void ctrlSShortcutSavesTheActiveDocument() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path file = dir.resolve("shortcut.txt");
        Files.write(file, "before".getBytes(StandardCharsets.UTF_8));

        EditorWindowController controller = loadController();
        try {
            fx(() -> controller.openLocal(file.toString()));
            EditorText area = awaitEditor(controller, 0);
            fx(() -> {
                area.setText("after");
                Parent root = (Parent) tabPane(controller).getParent();
                root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "s", "s",
                        KeyCode.S, false, true, false, false));
            });
            awaitCondition(() -> "after".equals(readFileText(file)), 15000);
        } finally {
            controller.onWindowClosing();
        }
    }

    @Test
    void binaryFileOpensReadonly() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path file = dir.resolve("binary.dat");
        Files.write(file, new byte[]{'a', 0, 0, 'b'});

        EditorWindowController controller = loadController();
        try {
            fx(() -> controller.openLocal(file.toString()));
            EditorText area = awaitEditor(controller, 0);
            assertFalse(area.isEditable(), "二进制文件应以只读预览打开");
        } finally {
            controller.onWindowClosing();
        }
    }

    @Test
    void findSelectsMatchesAndReplaceAllRewritesContent() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path file = dir.resolve("find.txt");
        Files.write(file, "foo bar foo\n".getBytes(StandardCharsets.UTF_8));

        EditorWindowController controller = loadController();
        try {
            fx(() -> controller.openLocal(file.toString()));
            EditorText area = awaitEditor(controller, 0);
            fx(() -> {
                controller.setFindText("foo");
                controller.onFindNext();
            });
            awaitCondition(() -> "foo".equals(area.getSelectedText()));
            assertEquals("foo", area.getSelectedText());

            fx(() -> {
                controller.setReplaceText("baz");
                controller.onReplaceAll();
            });
            awaitCondition(() -> "baz bar baz\n".equals(area.getText()));
            assertEquals("baz bar baz\n", area.getText());
        } finally {
            controller.onWindowClosing();
        }
    }

    @Test
    void unsavedChangesAreRestoredOnReopen() throws Exception {
        Path dir = Files.createTempDirectory("editor-test");
        Path file = dir.resolve("restore.txt");
        Files.write(file, "original\n".getBytes(StandardCharsets.UTF_8));

        EditorWindowController first = loadController();
        fx(() -> first.openLocal(file.toString()));
        EditorText area = awaitEditor(first, 0);
        fx(() -> area.appendText("edited\n"));
        awaitCondition(() -> controllerDirtyOf(first));
        first.onWindowClosing(); // 未保存内容应落盘到会话恢复存储

        // 模拟下次打开同一会话：新控制器重新打开同一本地文件
        EditorWindowController second = loadController();
        try {
            fx(() -> second.openLocal(file.toString()));
            EditorText restored = awaitEditor(second, 0);
            awaitCondition(() -> "original\nedited\n".equals(restored.getText()));
            assertEquals("original\nedited\n", restored.getText());
            assertTrue(controllerDirtyOf(second), "恢复的内容应标记为脏");
        } finally {
            second.onWindowClosing();
        }
    }

    // ==================== 工具 ====================

    private static EditorWindowController loadController() throws Exception {
        FXMLLoader loader = new FXMLLoader(EditorWindowTest.class.getResource("/com/runzhi/aishell/editor-window.fxml"));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();
        assertNotNull(root);
        EditorWindowController controller = loader.getController();
        assertNotNull(controller);
        return controller;
    }

    private static TabPane tabPane(EditorWindowController controller) {
        try {
            java.lang.reflect.Field field = EditorWindowController.class.getDeclaredField("tabPane");
            field.setAccessible(true);
            return (TabPane) field.get(controller);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean controllerDirtyOf(EditorWindowController controller) {
        try {
            TabPane pane = tabPane(controller);
            AtomicBoolean dirty = new AtomicBoolean();
            fx(() -> {
                if (pane.getSelectionModel().getSelectedItem() == null) return;
                Object userData = pane.getSelectionModel().getSelectedItem().getUserData();
                if (userData instanceof EditorTab tab) dirty.set(tab.document().dirty());
            });
            return dirty.get();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static EditorText awaitEditor(EditorWindowController controller, int tabIndex) throws Exception {
        AtomicReference<EditorText> area = new AtomicReference<>();
        awaitCondition(() -> {
            fxGet(area, controller, tabIndex);
            return area.get() != null;
        });
        return area.get();
    }

    private static void fxGet(AtomicReference<EditorText> sink, EditorWindowController controller, int tabIndex) {
        try {
            TabPane pane = tabPane(controller);
            if (pane.getTabs().isEmpty()) return;
            Object userData = pane.getTabs().get(tabIndex).getUserData();
            if (userData instanceof EditorTab tab) sink.set(tab.editor());
        } catch (Exception ignored) {
        }
    }

    private static String readFileText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        awaitCondition(condition, 8000);
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "条件在超时内未满足");
    }

    private static void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
