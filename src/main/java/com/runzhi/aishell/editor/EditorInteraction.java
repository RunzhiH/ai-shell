/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import com.runzhi.aishell.common.i18n.I18n;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 外部编辑器的 UI 交互实现：所有弹窗在 FX 线程执行；后台线程通过闭等待结果。
 *
 * <p>状态消息可选地回写到调用方提供的接收器（编辑器窗口的状态栏或文件面板的状态行）。
 */
public final class EditorInteraction implements ExternalEditorService.Interaction {

    private final Consumer<String> statusSink;

    public EditorInteraction() {
        this(null);
    }

    public EditorInteraction(Consumer<String> statusSink) {
        this.statusSink = statusSink;
    }

    @Override
    public void notify(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    @Override
    public ExternalEditorService.UploadDecision askUpload(String remotePath, String message) {
        return runOnFxAndGet(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(I18n.tr("editor.external.uploadPrompt"));
            alert.setHeaderText(message);
            ButtonType upload = new ButtonType(I18n.tr("editor.external.upload"), ButtonBar.ButtonData.YES);
            ButtonType keepLocal = new ButtonType(I18n.tr("editor.external.keepLocal"), ButtonBar.ButtonData.NO);
            ButtonType discard = new ButtonType(I18n.tr("editor.external.discard"), ButtonBar.ButtonData.OTHER);
            alert.getButtonTypes().setAll(upload, keepLocal, discard);
            Optional<ButtonType> chosen = alert.showAndWait();
            if (chosen.isEmpty() || chosen.get() == upload) return ExternalEditorService.UploadDecision.UPLOAD;
            if (chosen.get() == discard) return ExternalEditorService.UploadDecision.DISCARD;
            return ExternalEditorService.UploadDecision.KEEP_LOCAL;
        });
    }

    @Override
    public boolean isAutoUpload() {
        return EditorPreferences.autoUpload();
    }

    @Override
    public void statusMessage(String message) {
        if (statusSink == null) return;
        Platform.runLater(() -> statusSink.accept(message));
    }

    @Override
    public ExternalEditor chooseEditor(List<ExternalEditor> editors) {
        return runOnFxAndGet(() -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (ExternalEditor editor : editors) names.add(editor.name());
            javafx.scene.control.ChoiceDialog<String> dialog =
                    new javafx.scene.control.ChoiceDialog<>(names.get(0), names);
            dialog.setTitle(I18n.tr("editor.external.choose.title"));
            dialog.setHeaderText(I18n.tr("editor.external.choose.header"));
            dialog.setContentText(I18n.tr("editor.external.choose.content"));
            java.util.Optional<String> chosen = dialog.showAndWait();
            if (chosen.isEmpty()) return null;
            for (ExternalEditor editor : editors) {
                if (editor.name().equals(chosen.get())) return editor;
            }
            return null;
        });
    }

    private static <T> T runOnFxAndGet(java.util.function.Supplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }
}
