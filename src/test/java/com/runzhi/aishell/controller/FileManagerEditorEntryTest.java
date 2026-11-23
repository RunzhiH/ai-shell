package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件面板编辑器入口回归（方案 §9 FileEntryWorkflowTest）：
 * 双击文件调用打开（未连接时给出明确状态）、双击目录仍导航、右键菜单提供三类入口。
 */
class FileManagerEditorEntryTest {

    private FileManagerPanelController panel;
    private Label statusLabel;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        fx(() -> Platform.setImplicitExit(false));
    }

    @Test
    void doubleClickOnFileAttemptsOpenAndDirectoryNavigates() throws Exception {
        loadPanel();
        TableView<FileInfo> table = get(panel, "remoteFileTable");
        ObservableList<FileInfo> items = get(panel, "remoteFiles");
        FileInfo file = new FileInfo("readme.txt", "-rw-r--r--", 1, 0L);
        FileInfo dir = new FileInfo("log", "drwxr-xr-x", 0040000 | 0755, 0L);

        fx(() -> {
            items.setAll(dir, file);
            TextField pathField = get(panel, "pathField");
            pathField.setText("/var");
            new Scene(table, 600, 200);
            table.applyCss();
            table.layout();
        });

        // 未连接会话：双击文件应给出「未连接」状态，而不是无响应
        fx(() -> doubleClickRow(table, file));
        awaitCondition(() -> I18n.tr("fm.ssh.disconnected").equals(statusLabel.getText()));

        // 双击目录仍走原导航路径：路径栏进入子目录
        fx(() -> doubleClickRow(table, dir));
        awaitCondition(() -> "/var/log".equals(((TextField) get(panel, "pathField")).getText()));
        panel.onWindowClosing();
    }

    @Test
    void remoteContextMenuOffersBuiltinExternalAndSystemEntries() throws Exception {
        loadPanel();
        FileInfo file = new FileInfo("a.conf", "-rw-r--r--", 1, 0L);
        AtomicReference<ContextMenu> menu = new AtomicReference<>();
        fx(() -> {
            try {
                Method method = FileManagerPanelController.class.getDeclaredMethod(
                        "buildRemoteFileMenu", com.runzhi.aishell.ssh.domain.FileInfo.class);
                method.setAccessible(true);
                menu.set((ContextMenu) method.invoke(panel, file));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        assertNotNull(menu.get());
        // 平铺项：编辑 / 外部编辑器（二级菜单）/ 下载 / 详情
        assertEquals(4, menu.get().getItems().size());
        assertEquals(I18n.tr("fm.edit"), menu.get().getItems().get(0).getText());
        assertTrue(menu.get().getItems().get(1) instanceof javafx.scene.control.Menu,
                "外部编辑器应为二级菜单");
        Menu external = (Menu) menu.get().getItems().get(1);
        assertEquals(I18n.tr("fm.edit.external"), external.getText());
        // 二级菜单：自动选择 + 分隔符 + 至少一个检测到的编辑器（未检测到任何编辑器时退回 System default）
        assertTrue(external.getItems().size() >= 3, "二级菜单应包含自动选择与检测到的编辑器");
        assertEquals(I18n.tr("fm.edit.external.auto"), external.getItems().get(0).getText());
        assertNotEquals(I18n.tr("fm.edit.external.auto"), external.getItems().get(2).getText());
        panel.onWindowClosing();
    }

    @Test
    void localContextMenuOffersBuiltinExternalAndSystemEntries() throws Exception {
        loadPanel();
        FileInfo file = new FileInfo("local.txt", "-rw-r--r--", 1, 0L);
        AtomicReference<ContextMenu> menu = new AtomicReference<>();
        fx(() -> {
            try {
                Method method = FileManagerPanelController.class.getDeclaredMethod(
                        "buildLocalFileMenu", FileInfo.class);
                method.setAccessible(true);
                menu.set((ContextMenu) method.invoke(panel, file));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        assertNotNull(menu.get());
        // 平铺项：编辑 / 外部编辑器（二级菜单）/ 详情
        assertEquals(3, menu.get().getItems().size());
        assertTrue(menu.get().getItems().get(1) instanceof javafx.scene.control.Menu,
                "外部编辑器应为二级菜单");
        Menu external = (Menu) menu.get().getItems().get(1);
        assertTrue(external.getItems().size() >= 3);
        panel.onWindowClosing();
    }

    // ==================== 工具 ====================

    private void loadPanel() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/file-manager-panel.fxml"));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();
        panel = loader.getController();
        statusLabel = get(panel, "statusLabel");

        // 绑定一个未连接的会话：所有操作都应优雅地给出状态提示
        SSHService service = (SSHService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SSHService.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getTabConnection")) return null;
                    if (name.equals("getHostForTab")) return "a.example";
                    if (name.equals("getUsernameForTab")) return "root";
                    if (name.equals("getCurrentWorkingDirectoryForTab")) return "/var";
                    return null;
                });
        fx(() -> panel.setSshService(service, "tab-1"));
        assertNotNull(root);
    }

    /** 在已挂载场景的表格里找到目标行并模拟双击（等价于用户双击表格行）。 */
    @SuppressWarnings("unchecked")
    private static void doubleClickRow(TableView<FileInfo> table, FileInfo target) {
        for (javafx.scene.Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && target.equals(row.getItem())) {
                row.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, false, false, false,
                        new PickResult(table, 0, 0)));
                return;
            }
        }
        fail("未在表格中找到目标行: " + target.getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
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
