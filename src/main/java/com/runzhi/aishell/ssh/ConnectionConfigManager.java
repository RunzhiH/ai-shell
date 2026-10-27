/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.runzhi.aishell.common.logging.LoggerManager;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import org.slf4j.Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 连接配置管理器
 * 负责管理SSH连接配置的本地持久化存储
 */
public class ConnectionConfigManager {
    private static final Logger logger = LoggerManager.getLogger(ConnectionConfigManager.class);
    private static final String CONFIG_DIR = ".aishell";
    private static final String CONNECTIONS_FILE = "connections.json";

    private final Gson gson;
    private final Path configPath;

    public ConnectionConfigManager() {
        this.gson = new Gson();
        this.configPath = getUserConfigPath();
        initializeConfigDirectory();
    }

    /**
     * 获取用户配置目录路径
     */
    private Path getUserConfigPath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, CONFIG_DIR, CONNECTIONS_FILE);
    }

    /**
     * 初始化配置目录
     */
    private void initializeConfigDirectory() {
        try {
            Path configDir = configPath.getParent();
            if (configDir != null && !Files.exists(configDir)) {
                Files.createDirectories(configDir);
                logger.info("Created config directory: {}", configDir);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize config directory: {}", e.getMessage(), e);
        }
    }

    /**
     * 保存连接配置列表到本地文件
     */
    public boolean saveConnections(List<ConnectionParams> connections) {
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            String json = gson.toJson(connections);
            writer.write(json);
            writer.flush();
            logger.info("Saved {} connections to {}", connections.size(), configPath);
            return true;
        } catch (IOException e) {
            logger.error("Failed to save connections: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从本地文件加载连接配置列表
     */
    public List<ConnectionParams> loadConnections() {
        try {
            if (!Files.exists(configPath)) {
                logger.info("Connections file does not exist: {}", configPath);
                return new ArrayList<>();
            }

            String json = Files.readString(configPath);
            Type listType = new TypeToken<List<ConnectionParams>>() {
            }.getType();
            List<ConnectionParams> connections = gson.fromJson(json, listType);

            if (connections == null) {
                logger.warn("Loaded null connections list, returning empty list");
                return new ArrayList<>();
            }

            logger.info("Loaded {} connections from {}", connections.size(), configPath);
            return connections;
        } catch (IOException e) {
            logger.error("Failed to load connections: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 添加单个连接配置
     */
    public boolean addConnection(ConnectionParams connection) {
        List<ConnectionParams> connections = loadConnections();

        // 检查是否已存在相同配置（基于host, port, username）
        boolean exists = connections.stream()
                .anyMatch(conn -> conn.getHost().equals(connection.getHost()) &&
                        conn.getPort() == connection.getPort() &&
                        conn.getUsername().equals(connection.getUsername()));

        if (exists) {
            logger.warn("Connection already exists: {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());
            return false;
        }

        connections.add(connection);
        return saveConnections(connections);
    }

    /**
     * 更新连接配置
     */
    public boolean updateConnection(ConnectionParams oldConnection, ConnectionParams newConnection) {
        List<ConnectionParams> connections = loadConnections();

        // 查找并替换旧配置
        for (int i = 0; i < connections.size(); i++) {
            ConnectionParams conn = connections.get(i);
            
            // 比较连接参数时考虑更多细节，包括处理null值
            if (areConnectionParamsEqual(conn, oldConnection)) {
                connections.set(i, newConnection);
                boolean saved = saveConnections(connections);
                if (saved) {
                    logger.info("Successfully updated connection: {}@{}:{}", 
                            newConnection.getUsername(), newConnection.getHost(), newConnection.getPort());
                } else {
                    logger.error("Failed to save updated connection: {}@{}:{}", 
                            newConnection.getUsername(), newConnection.getHost(), newConnection.getPort());
                }
                return saved;
            }
        }

        logger.warn("Connection not found for update: {}@{}:{}", 
                oldConnection.getUsername(), oldConnection.getHost(), oldConnection.getPort());
        logger.debug("Available connections for comparison:");
        for (ConnectionParams conn : connections) {
            logger.debug("  - {}@{}:{}", conn.getUsername(), conn.getHost(), conn.getPort());
        }
        return false;
    }
    
    /**
     * 比较两个连接参数是否相等
     * 考虑到可能的null值情况
     */
    private boolean areConnectionParamsEqual(ConnectionParams conn1, ConnectionParams conn2) {
        if (conn1 == null && conn2 == null) return true;
        if (conn1 == null || conn2 == null) return false;
        
        // 比较主机、端口和用户名
        boolean hostsEqual = (conn1.getHost() == null && conn2.getHost() == null) || 
                             (conn1.getHost() != null && conn1.getHost().equals(conn2.getHost()));
        boolean portsEqual = conn1.getPort() == conn2.getPort();
        boolean usernamesEqual = (conn1.getUsername() == null && conn2.getUsername() == null) || 
                                 (conn1.getUsername() != null && conn1.getUsername().equals(conn2.getUsername()));
        
        // 也可以比较私钥路径，但通常用户名+主机+端口已经足够唯一标识一个连接
        boolean privateKeyPathsEqual = (conn1.getPrivateKeyPath() == null && conn2.getPrivateKeyPath() == null) || 
                                       (conn1.getPrivateKeyPath() != null && conn1.getPrivateKeyPath().equals(conn2.getPrivateKeyPath()));
        
        return hostsEqual && portsEqual && usernamesEqual;
    }

    /**
     * 删除连接配置
     */
    public boolean removeConnection(ConnectionParams connection) {
        List<ConnectionParams> connections = loadConnections();
        boolean removed = connections.removeIf(conn ->
                conn.getHost().equals(connection.getHost()) &&
                        conn.getPort() == connection.getPort() &&
                        conn.getUsername().equals(connection.getUsername())
        );

        if (removed) {
            boolean saved = saveConnections(connections);
            if (saved) {
                logger.info("Removed connection: {}@{}:{}",
                        connection.getUsername(), connection.getHost(), connection.getPort());
            }
            return saved;
        } else {
            logger.warn("Connection not found for removal: {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());
            return false;
        }
    }

    /**
     * 根据主机地址查找连接配置
     */
    public List<ConnectionParams> findConnectionsByHost(String host) {
        return loadConnections().stream()
                .filter(conn -> conn.getHost().equals(host))
                .collect(Collectors.toList());
    }

    /**
     * 根据用户名查找连接配置
     */
    public List<ConnectionParams> findConnectionsByUsername(String username) {
        return loadConnections().stream()
                .filter(conn -> conn.getUsername().equals(username))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有连接配置数量
     */
    public int getConnectionCount() {
        return loadConnections().size();
    }

    /**
     * 清空所有连接配置
     */
    public boolean clearAllConnections() {
        return saveConnections(new ArrayList<>());
    }
}