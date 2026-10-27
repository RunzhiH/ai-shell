/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.application.Platform;
import javafx.scene.Scene;
import lombok.extern.slf4j.Slf4j;

/**
 * 应用主题管理器。
 * <p>
 * 深色默认使用 style.css；浅色入口 light-theme.css 导入同一套组件后覆盖颜色。
 * 每个场景只需加载当前主题入口，确保窗口与对话框共用组件规则。
 */
@Slf4j
public class ThemeManager {
    private static final ThemeManager INSTANCE = new ThemeManager();

    public enum Theme {
        DARK("深色", "/com/runzhi/aishell/style.css"),
        LIGHT("浅色", "/com/runzhi/aishell/light-theme.css"),
        AUTO("跟随系统", "/com/runzhi/aishell/style.css");

        private final String displayName;
        private final String cssPath;

        Theme(String displayName, String cssPath) {
            this.displayName = displayName;
            this.cssPath = cssPath;
        }

        public String getDisplayName() { return displayName; }
        public String getCssPath() { return cssPath; }
    }

    private Theme currentTheme = Theme.DARK;

    private ThemeManager() {}

    public static ThemeManager getInstance() { return INSTANCE; }

    public Theme getCurrentTheme() { return currentTheme; }

    public void applyTheme(Scene scene, Theme theme) {
        if (scene == null) {
            log.warn("Scene 为空，无法应用主题");
            return;
        }
        this.currentTheme = theme;

        Runnable apply = () -> {
            scene.getStylesheets().clear();
            String css = getClass().getResource(theme.getCssPath()).toExternalForm();
            scene.getStylesheets().add(css);
            log.info("主题已切换为：{} ({})", theme.getDisplayName(), theme.getCssPath());
        };

        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    public void applyThemeByName(Scene scene, String name) {
        Theme theme = switch (name) {
            case "浅色", "light", "LIGHT" -> Theme.LIGHT;
            case "深色", "dark", "DARK" -> Theme.DARK;
            default -> Theme.DARK;
        };
        applyTheme(scene, theme);
    }

    /** 启动时完整样式表列表（默认深色单文件） */
    public String[] getStylesheetsFor(Theme theme) {
        String css = getClass().getResource(theme.getCssPath()).toExternalForm();
        return new String[]{css};
    }

    /** 公开资源解析 */
    public String resolvePublic(String resourcePath) {
        try {
            var url = getClass().getResource(resourcePath);
            return url == null ? null : url.toExternalForm();
        } catch (Exception e) {
            log.warn("解析样式资源失败: {}", resourcePath, e);
            return null;
        }
    }
}
