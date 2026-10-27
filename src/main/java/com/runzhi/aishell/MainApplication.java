/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell;

import com.runzhi.aishell.common.ai.AIShellModuleManager;
import com.runzhi.aishell.common.util.ThreadPoolManager;
import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MainApplication extends Application {
    private static final AtomicBoolean SHUTDOWN = new AtomicBoolean();

    @Override
    public void start(Stage stage) throws IOException {
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getResource("/com/runzhi/aishell/main-view.fxml")));
            loader.setResources(I18n.bundle());
            Parent root = loader.load();
            Scene scene = new Scene(root, 1200, 800);
            
            // V1：深色 Token + 组件样式（终端工作台）
            var themeManager = com.runzhi.aishell.common.util.ThemeManager.getInstance();
            String[] sheets = themeManager.getStylesheetsFor(themeManager.getCurrentTheme());
            if (sheets != null && sheets.length > 0) {
                for (String sheet : sheets) {
                    if (sheet != null) {
                        scene.getStylesheets().add(sheet);
                    }
                }
                log.debug("Loaded stylesheets: {}", String.join(", ", sheets));
            } else {
                log.warn("Failed to load stylesheets, application will run without global styles");
            }
            
            // 设置窗口图标（标题栏与任务栏）
            loadStageIcons(stage);

            stage.setTitle("AIShell - SSH & AI Assistant");
            stage.setScene(scene);
            stage.show();

            // 设置模块管理器的主舞台
            AIShellModuleManager moduleManager = AIShellModuleManager.getInstance();
            moduleManager.getNavigationManager().setPrimaryStage(stage);

            // 注册程序关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdownServices();
            }, "aishell-shutdown"));
        } catch (IOException e) {
            log.error("Failed to start application", e);
            throw e;
        }
    }

    @Override
    public void stop() {
        shutdownServices();
    }

    /**
     * 为窗口加载多分辨率图标，标题栏与任务栏会按尺寸自动选用。
     */
    private void loadStageIcons(Stage stage) {
        String[] iconPaths = {
                "/com/runzhi/aishell/app-icon-16.png",
                "/com/runzhi/aishell/app-icon-32.png",
                "/com/runzhi/aishell/app-icon-64.png",
                "/com/runzhi/aishell/app-icon-256.png"
        };
        for (String iconPath : iconPaths) {
            try (InputStream in = MainApplication.class.getResourceAsStream(iconPath)) {
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                } else {
                    log.warn("Stage icon not found: {}", iconPath);
                }
            } catch (Exception e) {
                log.warn("Failed to load stage icon: {}", iconPath, e);
            }
        }
    }

    private static void shutdownServices() {
        if (!SHUTDOWN.compareAndSet(false, true)) return;
        log.info("Application closing...");
        try {
            AIShellModuleManager.getInstance().shutdown();
        } finally {
            ThreadPoolManager.getInstance().shutdownNow();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
