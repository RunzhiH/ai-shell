/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh.domain;

import com.runzhi.aishell.common.util.PasswordEncryptionUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * SSH连接参数类
 * 使用Lombok减少样板代码
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class ConnectionParams {
    // 用户为这条连接起的名字，用于列表显示；为空时回退为 host:port - username
    private String profileName;
    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKeyPath;
    /** 分组（环境），用于侧栏会话树分组；旧配置没有该字段时归入默认分组 */
    private String group;
    
    // 加密标志，表示密码是否已加密
    private boolean passwordEncrypted = true;
    
    // 添加上次使用时间字段
    private LocalDateTime lastUsed;
    
    // 添加连接状态字段
    private boolean connected = false;

    /** 完整连接端点：user@host:port；用户名缺失时退化为 host:port。 */
    public String displayEndpoint() {
        String hostValue = host == null ? "" : host;
        String user = username == null || username.isBlank() ? "" : username + "@";
        return user + hostValue + ":" + port;
    }

    /** 可读目标：优先使用用户命名的连接名，否则使用完整端点。 */
    public String displayTarget() {
        if (profileName != null && !profileName.isBlank()) return profileName;
        return displayEndpoint();
    }

    /**
     * 使用默认端口22的构造函数
     */
    public ConnectionParams(String host, String username, String password, String privateKeyPath) {
        this.host = host;
        this.port = 22;
        this.username = username;
        this.setPassword(password); // 使用setter方法进行加密
        this.privateKeyPath = privateKeyPath;
    }
    
    /**
     * 获取解密后的密码
     */
    public String getDecryptedPassword() {

        if (password == null || password.isEmpty()) {
            return password;
        }
        if (passwordEncrypted) {
            return PasswordEncryptionUtil.decryptPassword(password);
        } else {
            return password;
        }
    }
    
    /**
     * 设置密码并加密存储
     */
    public void setPassword(String password) {

        if (password == null || password.isEmpty()) {
            this.password = password;
        } else {
            this.password = PasswordEncryptionUtil.encryptPassword(password);
            this.passwordEncrypted = true;
        }
    }
    
    /**
     * 设置未加密的密码（仅用于内部使用）
     */
    public void setPlainPassword(String plainPassword) {
        this.password = plainPassword;
        this.passwordEncrypted = false;
    }

    /**
     * 返回剔除密码字段的副本，用于云同步等外部导出。
     * <p>
     * 密码即使已加密也不应离开本机（加密密钥就在同一份代码里，等同明文），
     * 同步前必须显式清空。
     */
    public ConnectionParams sanitizedForExport() {
        ConnectionParams copy = new ConnectionParams();
        copy.profileName = this.profileName;
        copy.host = this.host;
        copy.port = this.port;
        copy.username = this.username;
        copy.password = null;
        copy.passwordEncrypted = false;
        copy.privateKeyPath = this.privateKeyPath;
        copy.lastUsed = this.lastUsed;
        copy.connected = this.connected;
        return copy;
    }
}