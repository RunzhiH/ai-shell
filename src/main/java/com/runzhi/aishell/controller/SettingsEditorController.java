/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import com.runzhi.aishell.common.i18n.I18n;
import com.runzhi.aishell.editor.EditorPreferences;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;

/**
 * 编辑器设置页：沿用设置页 Preferences 读写与「应用/保存」模式（方案 §4.5 / §6）。
 */
public class SettingsEditorController {

    @FXML private ComboBox<String> openModeCombo;
    @FXML private javafx.scene.control.TextField commandField;
    @FXML private CheckBox autoUploadCheck;
    @FXML private Spinner<Integer> maxInlineSpinner;
    @FXML private Spinner<Integer> maxExternalSpinner;
    @FXML private ComboBox<String> fallbackEncodingCombo;
    @FXML private Spinner<Integer> retentionDaysSpinner;

    @FXML
    private void initialize() {
        openModeCombo.setItems(FXCollections.observableArrayList(
                I18n.tr("settings.editor.openMode.builtin"),
                I18n.tr("settings.editor.openMode.external"),
                I18n.tr("settings.editor.openMode.ask")));
        openModeCombo.setValue(openModeLabel(EditorPreferences.openMode()));

        commandField.setText(EditorPreferences.externalCommand());
        autoUploadCheck.setSelected(EditorPreferences.autoUpload());
        maxInlineSpinner.getValueFactory().setValue(EditorPreferences.maxInlineBytes() / (1024 * 1024));
        maxExternalSpinner.getValueFactory().setValue(EditorPreferences.maxExternalBytes() / (1024 * 1024));
        fallbackEncodingCombo.setItems(FXCollections.observableArrayList("UTF-8", "GBK", "Big5", "Shift_JIS"));
        fallbackEncodingCombo.setValue(EditorPreferences.fallbackEncoding());
        retentionDaysSpinner.getValueFactory().setValue(EditorPreferences.tempRetentionDays());
    }

    public void saveSettings() {
        EditorPreferences.setOpenMode(parseOpenMode(openModeCombo.getValue()));
        EditorPreferences.setExternalCommand(commandField.getText());
        EditorPreferences.setAutoUpload(autoUploadCheck.isSelected());
        EditorPreferences.setMaxInlineBytes(safeValue(maxInlineSpinner) * 1024 * 1024);
        EditorPreferences.setMaxExternalBytes(safeValue(maxExternalSpinner) * 1024 * 1024);
        EditorPreferences.setFallbackEncoding(fallbackEncodingCombo.getValue());
        EditorPreferences.setTempRetentionDays(safeValue(retentionDaysSpinner));
    }

    private static int safeValue(Spinner<Integer> spinner) {
        Integer value = spinner.getValue();
        return value == null ? 0 : value;
    }

    private static EditorPreferences.OpenMode parseOpenMode(String label) {
        if (label == null) return EditorPreferences.OpenMode.BUILTIN;
        return switch (label) {
            case "external", "外部编辑器", "External editor" -> EditorPreferences.OpenMode.EXTERNAL;
            case "ask", "每次询问", "Ask every time" -> EditorPreferences.OpenMode.ASK;
            default -> EditorPreferences.OpenMode.BUILTIN;
        };
    }

    private static String openModeLabel(EditorPreferences.OpenMode mode) {
        if (mode == null) return I18n.tr("settings.editor.openMode.builtin");
        return switch (mode) {
            case EXTERNAL -> I18n.tr("settings.editor.openMode.external");
            case ASK -> I18n.tr("settings.editor.openMode.ask");
            default -> I18n.tr("settings.editor.openMode.builtin");
        };
    }
}
