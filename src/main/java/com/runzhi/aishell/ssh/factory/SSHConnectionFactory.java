/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh.factory;

import com.runzhi.aishell.common.config.AppConstants;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import lombok.extern.slf4j.Slf4j;

/**
 * SSH连接工厂类
 * 用于创建和管理SSH连接实例
 */
@Slf4j
public class SSHConnectionFactory {

    /**
     * 根据连接参数创建SSH连接管理器
     *
     * @param params 连接参数
     * @return SSH连接管理器实例
     */
    public static SSHConnectionManager createConnection(ConnectionParams params) {
        return createConnection(params, null);
    }
    
    /**
     * 根据连接参数和tabId创建SSH连接管理器
     *
     * @param params 连接参数
     * @param tabId  标签页ID
     * @return SSH连接管理器实例
     */
    public static SSHConnectionManager createConnection(ConnectionParams params, String tabId) {
        log.debug("Creating SSH connection for: {}@{}:{} with tabId: {}",
                params.getUsername(), params.getHost(), params.getPort(), tabId);

        return new SSHConnectionManager(params, tabId);
    }

    /**
     * 根据连接参数创建SSH连接管理器
     *
     * @param host           主机地址
     * @param port           端口号
     * @param username       用户名
     * @param password       密码
     * @param privateKeyPath 私钥路径
     * @return SSH连接管理器实例
     */
    public static SSHConnectionManager createConnection(String host, int port, String username,
                                                        String password, String privateKeyPath) {
        return createConnection(host, port, username, password, privateKeyPath, null);
    }
    
    /**
     * 根据连接参数和tabId创建SSH连接管理器
     *
     * @param host           主机地址
     * @param port           端口号
     * @param username       用户名
     * @param password       密码
     * @param privateKeyPath 私钥路径
     * @param tabId          标签页ID
     * @return SSH连接管理器实例
     */
    public static SSHConnectionManager createConnection(String host, int port, String username,
                                                        String password, String privateKeyPath, String tabId) {
        log.debug("Creating SSH connection for: {}@{}:{} with tabId: {}", username, host, port, tabId);

        return new SSHConnectionManager(
                host, port, username, password, privateKeyPath, tabId
        );
    }

    /**
     * 创建具有默认值的连接参数
     *
     * @return 默认连接参数
     */
    public static ConnectionParams createDefaultParams() {
        ConnectionParams params = new ConnectionParams();
        params.setHost(AppConstants.DEFAULT_SSH_HOST);
        params.setPort(AppConstants.DEFAULT_SSH_PORT);
        params.setUsername(AppConstants.DEFAULT_SSH_USERNAME);
        params.setPassword(AppConstants.DEFAULT_SSH_PASSWORD);
        params.setPrivateKeyPath(null);
        return params;
    }
}
