package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.config.ConfigManager;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ParameterManager;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 侧栏会话树（v6 阶段 2）：分组结构、筛选、单击只选中、
 * 双击 / Enter 才激活。连接数据经由 ConfigManager 注入（user.home 已隔离）。
 */
class ServerTreeBehaviorTest {

    private MainViewController controller;
    private TreeView<ConnectionParams> tree;

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

    private ConnectionParams conn(String name, String host, String group) {
        ConnectionParams params = new ConnectionParams();
        params.setProfileName(name);
        params.setHost(host);
        params.setPort(22);
        params.setUsername("root");
        params.setPlainPassword("irrelevant");
        params.setGroup(group);
        return params;
    }

    @BeforeEach
    void loadMainView() throws Exception {
        List<ConnectionParams> seeded = new ArrayList<>();
        seeded.add(conn("prod-web-01", "192.168.1.10", "生产环境"));
        seeded.add(conn("prod-api-01", "192.168.1.11", "生产环境"));
        seeded.add(conn("prod-db-01", "192.168.1.12", "生产环境"));
        seeded.add(conn("staging-01", "10.0.0.20", "测试环境"));
        ConfigManager.getInstance().saveConnectionConfigs(seeded);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<MainViewController> captured = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/runzhi/aishell/main-view.fxml"));
                loader.setResources(I18n.bundle());
                Parent root = loader.load();
                captured.set(loader.getController());
            } catch (Exception failure) {
                failure.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "main-view.fxml load timed out");
        controller = captured.get();
        assertNotNull(controller, "MainViewController 未注入");

        Field treeField = MainViewController.class.getDeclaredField("serverTree");
        treeField.setAccessible(true);
        tree = (TreeView<ConnectionParams>) treeField.get(controller);
        assertNotNull(tree, "serverTree 未注入");
    }

    private void waitForFxEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private List<TreeItem<ConnectionParams>> leaves() {
        List<TreeItem<ConnectionParams>> result = new ArrayList<>();
        for (TreeItem<ConnectionParams> group : tree.getRoot().getChildren()) {
            result.addAll(group.getChildren());
        }
        return result;
    }

    @Test
    void treeGroupsConnectionsByEnvironment() {
        assertEquals(2, tree.getRoot().getChildren().size());
        // 每组子节点数与种子一致（3 生产 + 1 测试）
        assertEquals(4, leaves().size());
        TreeItem<ConnectionParams> first = tree.getRoot().getChildren().get(0);
        assertEquals(3, first.getChildren().size());
        TreeItem<ConnectionParams> second = tree.getRoot().getChildren().get(1);
        assertEquals(1, second.getChildren().size());
    }

    @Test
    void filterNarrowsTreeAndDropsEmptyGroups() throws Exception {
        Field filterField = MainViewController.class.getDeclaredField("serverTreeFilter");
        filterField.setAccessible(true);
        javafx.scene.control.TextField filter = (javafx.scene.control.TextField) filterField.get(controller);
        filter.setText("staging");
        waitForFxEvents();

        assertEquals(1, tree.getRoot().getChildren().size());
        assertEquals(1, leaves().size());
        assertEquals("staging-01", leaves().get(0).getValue().getProfileName());

        filter.clear();
        waitForFxEvents();
        assertEquals(4, leaves().size());
    }

    @Test
    void singleClickOnlySelectsWithoutActivating() {
        TreeItem<ConnectionParams> leaf = leaves().stream()
                .filter(item -> "staging-01".equals(item.getValue().getProfileName()))
                .findFirst().orElseThrow();
        tree.getSelectionModel().select(leaf);

        // 单击只选中：选中项是目标连接，但不产生连接参数（旧卡片行为是单击即连接）
        assertEquals("staging-01", controller.selectedTreeConnection().getProfileName());
        assertNull(ParameterManager.getInstance().get("connection_params", ConnectionParams.class));
    }

    @Test
    void activationGuardsRejectGroupSelection() {
        TreeItem<ConnectionParams> group = tree.getRoot().getChildren().get(0);
        tree.getSelectionModel().select(group);
        // 选中分组头（值 null）时不激活
        assertFalse(controller.activateSelectedTreeConnection());
        assertNull(controller.selectedTreeConnection());
    }

    @Test
    void doubleClickAndEnterHandlersAreWiredForActivation() {
        assertNotNull(tree.getOnMouseClicked(), "双击激活处理器未挂载");
        assertNotNull(tree.getOnKeyPressed(), "Enter 激活处理器未挂载");
    }
}
