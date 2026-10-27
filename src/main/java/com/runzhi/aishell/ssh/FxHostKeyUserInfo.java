/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.UserInfo;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;

/** JavaFX host-key confirmation used with JSch StrictHostKeyChecking=ask. */
final class FxHostKeyUserInfo implements UserInfo {
    private final String password;
    private final String passphrase;

    FxHostKeyUserInfo(String password) {
        this(password, null);
    }

    FxHostKeyUserInfo(String password, String passphrase) {
        this.password = password;
        this.passphrase = passphrase;
    }

    @Override public String getPassphrase() { return passphrase; }
    @Override public String getPassword() { return password; }
    @Override public boolean promptPassword(String message) { return false; }

    /**
     * 私钥未配置 passphrase 时弹窗询问，而不是恒返回 false（2026-10-23 审计 P1-25）。
     */
    @Override
    public boolean promptPassphrase(String message) {
        if (passphrase != null) return true;
        try {
            javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
            dialog.setTitle("SSH 私钥口令");
            dialog.setHeaderText("私钥已加密");
            dialog.setContentText(message);
            return dialog.showAndWait().filter(p -> !p.isEmpty()).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean promptYesNo(String message) {
        FutureTask<Boolean> prompt = new FutureTask<>(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("确认 SSH 主机指纹");
            alert.setHeaderText("首次连接到此服务器");
            alert.setContentText(message + "\n\n请核对服务器指纹。确认后将写入 ~/.ssh/known_hosts。");
            return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
        });
        if (Platform.isFxApplicationThread()) prompt.run();
        else Platform.runLater(prompt);
        try {
            return prompt.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            // 记录弹窗失败原因，而不是静默拒绝导致连接无说明地失败（2026-10-23 审计 P1-25）
            System.getLogger(FxHostKeyUserInfo.class.getName())
                    .log(System.Logger.Level.WARNING, "主机指纹确认弹窗失败", e.getCause());
            return false;
        }
    }

    @Override
    public void showMessage(String message) {
        Runnable show = () -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("SSH");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        };
        if (Platform.isFxApplicationThread()) show.run();
        else Platform.runLater(show);
    }
}
