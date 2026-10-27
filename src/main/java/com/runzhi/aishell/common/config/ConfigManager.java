/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.runzhi.aishell.common.logging.LoggerManager;
import com.runzhi.aishell.common.util.LocalDateTimeAdapter;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import lombok.extern.slf4j.Slf4j;

import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理器
 * 负责管理应用程序的配置信息
 */
@Slf4j
public class ConfigManager {
    private static final org.slf4j.Logger logger = LoggerManager.getLogger(ConfigManager.class);
    private static final String CONFIG_DIR = ".aishell";
    private static final String CONNECTIONS_FILE = "connections.json";
    private static final String AI_CONFIG_FILE = "ai_configs.json";
    private static final String APP_CONFIG_FILE = "app_configs.json";

    private final Gson gson;
    private final Path connectionsPath;
    private final Path aiConfigPath;
    private final Path appConfigPath;

    private ConfigManager() {
        this.gson = new GsonBuilder()
                .registerTypeAdapter(java.time.LocalDateTime.class, new LocalDateTimeAdapter())
                .create();
        this.connectionsPath = getUserConfigPath(CONNECTIONS_FILE);
        this.aiConfigPath = getUserConfigPath(AI_CONFIG_FILE);
        this.appConfigPath = getUserConfigPath(APP_CONFIG_FILE);
        initializeConfigDirectory();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /**
     * 获取用户配置目录路径
     */
    private Path getUserConfigPath(String fileName) {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, CONFIG_DIR, fileName);
    }

    /**
     * 初始化配置目录
     */
    private void initializeConfigDirectory() {
        try {
            Path configDir = getUserConfigPath("");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                logger.info("Created config directory: {}", configDir.toAbsolutePath());
            }
        } catch (Exception e) {
            logger.error("Failed to initialize config directory", e);
        }
    }

    /**
     * 保存连接配置
     */
    public boolean saveConnectionConfig(ConnectionParams connection) {
        try {
            List<ConnectionParams> connections = loadConnectionConfigs();
            connections.add(connection);
            saveConnectionConfigs(connections);

            logger.info("Saved connection config for: {}:{}", connection.getHost(), connection.getPort());
            return true;
        } catch (Exception e) {
            logger.error("Failed to save connection config", e);
            return false;
        }
    }

    /**
     * 保存连接配置列表
     */
    public boolean saveConnectionConfigs(List<ConnectionParams> connections) {
        try {
            // 确保目录存在
            initializeConfigDirectory();
            
            try (FileWriter writer = new FileWriter(connectionsPath.toFile())) {
                gson.toJson(connections, writer);
            }

            logger.info("Saved {} connection configs", connections.size());
            return true;
        } catch (Exception e) {
            logger.error("Failed to save connection configs", e);
            return false;
        }
    }

    /**
     * 加载连接配置
     */
    public List<ConnectionParams> loadConnectionConfigs() {
        try {
            if (!Files.exists(connectionsPath)) {
                logger.info("Connections file does not exist, returning empty list");
                return new ArrayList<>();
            }
            
            // 检查文件大小和内容，如果为空或只有空白字符则返回空列表
            if (Files.size(connectionsPath) == 0) {
                logger.info("Connections file is empty, returning empty list");
                return new ArrayList<>();
            }
            
            // 读取文件内容以检查是否只包含空白字符
            String content = Files.readString(connectionsPath);
            if (content.trim().isEmpty()) {
                logger.info("Connections file contains only whitespace, returning empty list");
                return new ArrayList<>();
            }

            try (FileReader reader = new FileReader(connectionsPath.toFile())) {
                Type listType = new TypeToken<List<ConnectionParams>>(){}.getType();
                List<ConnectionParams> connections = gson.fromJson(reader, listType);
                if (connections == null) {
                    logger.info("Connections file is empty, returning empty list");
                    return new ArrayList<>();
                }
                logger.info("Loaded {} connection configs", connections.size());
                return connections;
            }
        } catch (java.io.EOFException e) {
            logger.error("Connection configs file is empty or corrupted: {}", e.getMessage());
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Failed to load connection configs", e);
            return new ArrayList<>();
        }
    }

    /**
     * 更新连接配置
     */
    public boolean updateConnectionConfig(ConnectionParams oldConnection, ConnectionParams newConnection) {
        try {
            List<ConnectionParams> connections = loadConnectionConfigs();
            for (int i = 0; i < connections.size(); i++) {
                ConnectionParams conn = connections.get(i);
                if (conn.getHost().equals(oldConnection.getHost()) &&
                    conn.getPort() == oldConnection.getPort() &&
                    conn.getUsername().equals(oldConnection.getUsername())) {
                    connections.set(i, newConnection);
                    saveConnectionConfigs(connections);
                    logger.info("Updated connection config for: {}:{}", oldConnection.getHost(), oldConnection.getPort());
                    return true;
                }
            }
            logger.warn("Connection config not found for update: {}:{}", oldConnection.getHost(), oldConnection.getPort());
            return false;
        } catch (Exception e) {
            logger.error("Failed to update connection config", e);
            return false;
        }
    }

    /**
     * 删除连接配置
     */
    public boolean removeConnectionConfig(ConnectionParams connection) {
        try {
            List<ConnectionParams> connections = loadConnectionConfigs();
            boolean removed = connections.removeIf(conn -> 
                conn.getHost().equals(connection.getHost()) &&
                conn.getPort() == connection.getPort() &&
                conn.getUsername().equals(connection.getUsername())
            );
            
            if (removed) {
                saveConnectionConfigs(connections);
                logger.info("Removed connection config for: {}:{}", connection.getHost(), connection.getPort());
            } else {
                logger.warn("Connection config not found for removal: {}:{}", connection.getHost(), connection.getPort());
            }
            
            return removed;
        } catch (Exception e) {
            logger.error("Failed to remove connection config", e);
            return false;
        }
    }

    // 单例实例
    /**
     * 保存应用配置（包括主题）
     */
    public boolean saveAppConfig(String key, Object value) {
        try {
            // 加载现有配置
            java.util.Map<String, Object> config = loadAppConfig();
            
            // 更新配置
            config.put(key, value);
            
            // 保存配置
            initializeConfigDirectory();
            try (FileWriter writer = new FileWriter(appConfigPath.toFile())) {
                gson.toJson(config, writer);
            }
            
            logger.info("Saved app config: {}={}", key, value);
            return true;
        } catch (Exception e) {
            logger.error("Failed to save app config", e);
            return false;
        }
    }
    
    /**
     * 加载应用配置
     */
    public java.util.Map<String, Object> loadAppConfig() {
        try {
            if (!Files.exists(appConfigPath)) {
                logger.info("App config file does not exist, returning empty map");
                return new java.util.HashMap<>();
            }
            
            // 检查文件大小和内容
            if (Files.size(appConfigPath) == 0) {
                return new java.util.HashMap<>();
            }
            
            String content = Files.readString(appConfigPath);
            if (content.trim().isEmpty()) {
                return new java.util.HashMap<>();
            }
            
            Type mapType = new TypeToken<java.util.Map<String, Object>>(){}.getType();
            java.util.Map<String, Object> config = gson.fromJson(content, mapType);
            
            if (config == null) {
                return new java.util.HashMap<>();
            }
            
            logger.info("Loaded app config with {} entries", config.size());
            return config;
        } catch (Exception e) {
            logger.error("Failed to load app config", e);
            return new java.util.HashMap<>();
        }
    }
    
    /**
     * 获取特定配置值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAppConfig(String key, T defaultValue) {
        try {
            java.util.Map<String, Object> config = loadAppConfig();
            Object value = config.get(key);
            return value != null ? (T) value : defaultValue;
        } catch (Exception e) {
            logger.error("Failed to get app config: {}", key, e);
            return defaultValue;
        }
    }
    
    private static volatile ConfigManager instance;
}