/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.config.StartupPage;
import com.runzhi.aishell.common.config.WindowsAutoStartManager;
import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.common.util.ThemeManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

/**
 * Persists and applies the controls shown on the general settings page.
 *
 * <p>语言、主题、启动页统一以规范值持久化，界面显示本地化标签；
 * 历史存过的中文标签值在加载时自动迁移，旧用户无感知。
 */
public class SettingsGeneralController {
    @FXML private ComboBox<String> languageCombo;
    @FXML private ComboBox<String> themeCombo;
    @FXML private Spinner<Integer> fontSizeSpinner;
    @FXML private ComboBox<String> startupPageCombo;
    @FXML private Slider sidebarWidthSlider;
    @FXML private CheckBox autoSaveCheck;
    @FXML private CheckBox autoStartCheck;

    private final Preferences prefs = Preferences.userNodeForPackage(SettingsGeneralController.class);
    private final WindowsAutoStartManager autoStartManager = new WindowsAutoStartManager();

    @FXML
    private void initialize() {
        // 下拉项在代码里填充本地化标签（fx:value 无法解析 %key）
        themeCombo.setItems(FXCollections.observableArrayList(
                I18n.tr("settings.theme.light"), I18n.tr("settings.theme.dark"), I18n.tr("settings.theme.auto")));
        themeCombo.setValue(themeLabel(canonicalTheme(prefs.get("general.theme", "auto"))));

        startupPageCombo.setItems(FXCollections.observableArrayList(
                I18n.tr("settings.startup.connections"), I18n.tr("settings.startup.terminal"),
                I18n.tr("settings.startup.welcome")));
        startupPageCombo.setValue(startupPageLabel(startupPageFromPreference(
                prefs.get("general.startupPage", StartupPage.CONNECTIONS.name()))));

        languageCombo.setValue(I18n.currentLabel());
        fontSizeSpinner.getValueFactory().setValue(prefs.getInt("general.fontSize", 14));
        sidebarWidthSlider.setValue(prefs.getDouble("general.sidebarWidth", 280));
        autoSaveCheck.setSelected(prefs.getBoolean("general.autoSave", true));
        autoStartCheck.setDisable(!autoStartManager.isSupported());
        autoStartCheck.setSelected(autoStartManager.isEnabled());
    }

    public void saveSettings() {
        // 语言立即切换：此后新加载的页面用新语言，已打开的视图需重启后生效
        boolean languageChanged = I18n.setByLabel(languageCombo.getValue());

        String theme = canonicalTheme(themeCombo.getValue());
        prefs.put("general.theme", theme);
        prefs.putInt("general.fontSize", fontSizeSpinner.getValue());
        prefs.put("general.startupPage", startupPageFromPreference(startupPageCombo.getValue()).name());
        prefs.putDouble("general.sidebarWidth", sidebarWidthSlider.getValue());
        prefs.putBoolean("general.autoSave", autoSaveCheck.isSelected());
        try {
            autoStartManager.setEnabled(autoStartCheck.isSelected());
            prefs.putBoolean("general.autoStart", autoStartCheck.isSelected());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(I18n.tr("settings.autostart.failed", e.getMessage()), e);
        }

        ThemeManager.Theme selected = switch (theme) {
            case "light" -> ThemeManager.Theme.LIGHT;
            case "dark" -> ThemeManager.Theme.DARK;
            default -> ThemeManager.Theme.AUTO;
        };
        if (themeCombo.getScene() != null) {
            Scene targetScene = themeCombo.getScene();
            if (targetScene.getWindow() instanceof Stage stage && stage.getOwner() != null) {
                targetScene = stage.getOwner().getScene();
            }
            ThemeManager.getInstance().applyTheme(targetScene, selected);
        }

        if (languageChanged) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(I18n.tr("settings.language.changed.title"));
            alert.setHeaderText(null);
            alert.setContentText(I18n.tr("settings.language.changed.content"));
            alert.showAndWait();
        }
    }

    /** 主题规范值；兼容历史存储的中文标签 */
    private static String canonicalTheme(String stored) {
        if (stored == null) return "auto";
        return switch (stored) {
            case "浅色", "light" -> "light";
            case "深色", "dark" -> "dark";
            default -> "auto";
        };
    }

    private static String themeLabel(String canonical) {
        return switch (canonical) {
            case "light" -> I18n.tr("settings.theme.light");
            case "dark" -> I18n.tr("settings.theme.dark");
            default -> I18n.tr("settings.theme.auto");
        };
    }

    /** 启动页枚举；兼容历史存储的中文标签与枚举名 */
    private static StartupPage startupPageFromPreference(String stored) {
        if (stored == null) return StartupPage.CONNECTIONS;
        return switch (stored) {
            case "终端", "TERMINAL", "terminal" -> StartupPage.TERMINAL;
            case "欢迎页", "WELCOME", "welcome" -> StartupPage.WELCOME;
            default -> StartupPage.CONNECTIONS;
        };
    }

    private static String startupPageLabel(StartupPage page) {
        return switch (page) {
            case TERMINAL -> I18n.tr("settings.startup.terminal");
            case WELCOME -> I18n.tr("settings.startup.welcome");
            default -> I18n.tr("settings.startup.connections");
        };
    }
}
