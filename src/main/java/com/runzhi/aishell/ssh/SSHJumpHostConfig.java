/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.runzhi.aishell.common.util.PasswordEncryptionUtil;

import java.util.prefs.Preferences;

/** Global ProxyJump settings used when opening a new SSH connection. */
public record SSHJumpHostConfig(boolean enabled, String host, int port, String username,
                                String password, String privateKeyPath) {
    private static final Preferences PREFS = Preferences.userRoot().node("/com/runzhi/aishell/controller");

    public static SSHJumpHostConfig load() {
        String password = "";
        String encrypted = PREFS.get("ssh.jump.password", "");
        if (!encrypted.isBlank()) {
            try { password = PasswordEncryptionUtil.decryptPassword(encrypted); }
            catch (IllegalArgumentException ignored) { password = ""; }
        }
        return new SSHJumpHostConfig(PREFS.getBoolean("ssh.jump.enabled", false),
                PREFS.get("ssh.jump.host", ""), PREFS.getInt("ssh.jump.port", 22),
                PREFS.get("ssh.jump.username", ""), password,
                PREFS.get("ssh.jump.privateKey", ""));
    }

    public void validate() {
        if (!enabled) return;
        if (host == null || host.isBlank() || port < 1 || port > 65535
                || username == null || username.isBlank()) {
            throw new IllegalArgumentException("跳板机已启用，但主机、端口或用户名无效");
        }
        if ((password == null || password.isBlank())
                && (privateKeyPath == null || privateKeyPath.isBlank())) {
            throw new IllegalArgumentException("跳板机必须配置密码或私钥");
        }
    }
}
