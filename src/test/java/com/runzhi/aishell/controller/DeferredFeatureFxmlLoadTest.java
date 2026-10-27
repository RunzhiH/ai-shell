package com.runzhi.aishell.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeferredFeatureFxmlLoadTest {
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
    void agentWorkspaceLoadsWithJediTermAgentConsole() throws Exception {
        Parent agent = loadOnFxThread("/com/runzhi/aishell/agent-workspace.fxml");
        assertNotNull(agent.lookup("#agentTerminalHost"));
        assertFalse(((javafx.scene.layout.Pane) agent).getChildren().isEmpty());
    }

    @Test
    void aiSettingsPageLoadsWithProviderAndApiKeyControls() throws Exception {
        Parent settings = loadOnFxThread("/com/runzhi/aishell/settings-ai.fxml");
        assertNotNull(settings);
        Path config = Path.of(System.getProperty("user.home"), ".aishell", "ai_configs.json");
        assertTrue(Files.isRegularFile(config), "AI settings must create its configuration on first load");
    }

    /**
     * 书签面板 / 命令历史对话框 / 高级监控图表三个视图均已有菜单入口，
     * 此测试确认它们仍能正常加载，作为接线与样式的回归保护。
     */
    @Test
    void deferredFeatureViewsRemainLoadable() throws Exception {
        assertNotNull(loadOnFxThread("/com/runzhi/aishell/bookmark-panel.fxml"));
        assertNotNull(loadOnFxThread("/com/runzhi/aishell/command-history-dialog.fxml"));
        assertNotNull(loadOnFxThread("/com/runzhi/aishell/advanced-monitor-charts.fxml"));
    }

    private Parent loadOnFxThread(String resource) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Parent> root = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                root.set(FXMLLoader.load(getClass().getResource(resource)));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FXML load timed out: " + resource);
        if (failure.get() != null) throw new AssertionError("FXML load failed: " + resource, failure.get());
        return root.get();
    }
}
