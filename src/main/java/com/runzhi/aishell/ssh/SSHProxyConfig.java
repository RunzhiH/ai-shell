/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS4;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.Session;
import com.runzhi.aishell.common.util.PasswordEncryptionUtil;

import java.util.Locale;
import java.util.prefs.Preferences;

public record SSHProxyConfig(boolean enabled, String type, String host, int port,
                             String username, String password) {
    // SettingsSSHController historically stores these keys in this Preferences node.
    private static final Preferences PREFS = Preferences.userRoot().node("/com/runzhi/aishell/controller");

    public static SSHProxyConfig load() {
        String encryptedPassword = PREFS.get("ssh.proxy.password", "");
        String password = "";
        if (!encryptedPassword.isBlank()) {
            try { password = PasswordEncryptionUtil.decryptPassword(encryptedPassword); }
            catch (IllegalArgumentException ignored) { password = ""; }
        }
        return new SSHProxyConfig(PREFS.getBoolean("ssh.proxy.enabled", false),
                PREFS.get("ssh.proxy.type", "HTTP"), PREFS.get("ssh.proxy.host", ""),
                PREFS.getInt("ssh.proxy.port", 8080), PREFS.get("ssh.proxy.username", ""), password);
    }

    public void apply(Session session) {
        if (!enabled) return;
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("代理已启用，但主机或端口无效");
        }
        Proxy proxy = switch (type == null ? "HTTP" : type.toUpperCase(Locale.ROOT)) {
            case "SOCKS4" -> new ProxySOCKS4(host, port);
            case "SOCKS5" -> new ProxySOCKS5(host, port);
            case "HTTP" -> new ProxyHTTP(host, port);
            default -> throw new IllegalArgumentException("不支持的代理类型: " + type);
        };
        if (username != null && !username.isBlank()) {
            if (proxy instanceof ProxyHTTP value) value.setUserPasswd(username, password);
            else if (proxy instanceof ProxySOCKS4 value) value.setUserPasswd(username, password);
            else if (proxy instanceof ProxySOCKS5 value) value.setUserPasswd(username, password);
        }
        session.setProxy(proxy);
    }
}
