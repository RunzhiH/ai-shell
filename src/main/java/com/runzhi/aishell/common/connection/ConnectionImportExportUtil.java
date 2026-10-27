/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.connection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.runzhi.aishell.common.util.DialogUtils;
import com.runzhi.aishell.common.util.LocalDateTimeAdapter;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 连接配置导入导出工具类
 * 提供连接配置的导入和导出功能，方便用户备份和迁移连接配置
 */
@Slf4j
public class ConnectionImportExportUtil {
    
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(java.time.LocalDateTime.class, new LocalDateTimeAdapter())
            .create();
    
    /**
     * 导出连接配置到文件
     * 
     * @param connections 要导出的连接配置列表
     * @param filePath 输出文件路径
     * @return 是否导出成功
     */
    public static boolean exportConnections(List<ConnectionParams> connections, String filePath) {
        try (FileWriter writer = new FileWriter(filePath, StandardCharsets.UTF_8)) {
            com.google.gson.JsonArray exportData = new com.google.gson.JsonArray();
            for (ConnectionParams connection : connections) {
                com.google.gson.JsonObject record = gson.toJsonTree(connection).getAsJsonObject();
                // 本机密钥加密的密码无法安全迁移，也不应出现在明文导出文件中。
                record.remove("password");
                record.remove("passwordEncrypted");
                record.remove("connected");
                exportData.add(record);
            }
            String json = gson.toJson(exportData);
            writer.write(json);
            writer.flush();
            log.info("Exported {} connections to {}", connections.size(), filePath);
            return true;
        } catch (IOException e) {
            log.error("Failed to export connections to {}: {}", filePath, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 从文件导入连接配置
     * 
     * @param filePath 输入文件路径
     * @return 导入的连接配置列表
     */
    public static List<ConnectionParams> importConnections(String filePath) {
        try {
            String json = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
            
            // 检查JSON是否为空或空白
            if (json == null || json.trim().isEmpty()) {
                log.warn("Import file is empty or contains only whitespace: {}", filePath);
                return java.util.Collections.emptyList();
            }
            
            Type listType = new TypeToken<List<ConnectionParams>>() {}.getType();
            List<ConnectionParams> connections = gson.fromJson(json, listType);
            
            if (connections == null) {
                log.warn("Imported null connections list from {}, returning empty list", filePath);
                return java.util.Collections.emptyList();
            }
            
            List<ConnectionParams> validConnections = connections.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(ConnectionImportExportUtil::isValidConnection)
                    .collect(java.util.stream.Collectors.toList());
            log.info("Imported {} valid connections ({} rejected) from {}",
                    validConnections.size(), connections.size() - validConnections.size(), filePath);
            return validConnections;
        } catch (com.google.gson.JsonSyntaxException | java.io.EOFException e) {
            log.error("JSON parsing error when importing connections from {}: {}", filePath, e.getMessage());
            return java.util.Collections.emptyList();
        } catch (IOException e) {
            log.error("Failed to import connections from {}: {}", filePath, e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }
    
    /**
     * 显示文件选择对话框用于导出
     * 
     * @param ownerWindow 父窗口
     * @param connections 要导出的连接配置列表
     */
    public static void showExportFileChooser(Window ownerWindow, List<ConnectionParams> connections) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出连接配置");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON文件", "*.json"));
        fileChooser.setInitialFileName("aishell_connections.json");
        
        File file = fileChooser.showSaveDialog(ownerWindow);
        if (file != null) {
            boolean success = exportConnections(connections, file.getAbsolutePath());
            if (success) {
                DialogUtils.showInfoDialog("导出成功", "连接配置导出成功", 
                        "已成功导出 " + connections.size() + " 个连接配置到 " + file.getAbsolutePath());
            } else {
                DialogUtils.showErrorDialog("导出失败", "连接配置导出失败", 
                        "导出连接配置时发生错误，请检查文件权限或路径是否有效。");
            }
        }
    }
    
    /**
     * 显示文件选择对话框用于导入
     * 
     * @param ownerWindow 父窗口
     * @return 导入的连接配置列表
     */
    public static List<ConnectionParams> showImportFileChooser(Window ownerWindow) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导入连接配置");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON文件", "*.json"));
        
        File file = fileChooser.showOpenDialog(ownerWindow);
        if (file != null) {
            List<ConnectionParams> importedConnections = importConnections(file.getAbsolutePath());
            if (!importedConnections.isEmpty()) {
                DialogUtils.showInfoDialog("导入成功", "连接配置导入成功", 
                        "已成功导入 " + importedConnections.size() + " 个连接配置。");
                return importedConnections;
            } else {
                DialogUtils.showErrorDialog("导入失败", "连接配置导入失败", 
                        "无法从选定文件导入连接配置，请检查文件格式是否正确。");
            }
        }
        return java.util.Collections.emptyList();
    }
    
    /**
     * 合并导入的连接配置到现有配置
     * 
     * @param existingConnections 现有的连接配置列表
     * @param importedConnections 要导入的连接配置列表
     * @return 合并后的连接配置列表
     */
    public static List<ConnectionParams> mergeConnections(List<ConnectionParams> existingConnections, 
                                                         List<ConnectionParams> importedConnections) {
        // 创建一个包含现有连接的副本，用于合并
        List<ConnectionParams> mergedConnections = new java.util.ArrayList<>(existingConnections);
        
        // 遍历导入的连接，避免重复
        for (ConnectionParams importedConn : importedConnections) {
            boolean exists = false;
            for (ConnectionParams existingConn : existingConnections) {
                // 检查是否已存在相同的连接（基于host, port, username）
                if (java.util.Objects.equals(existingConn.getHost(), importedConn.getHost()) &&
                    existingConn.getPort() == importedConn.getPort() &&
                    java.util.Objects.equals(existingConn.getUsername(), importedConn.getUsername())) {
                    exists = true;
                    break;
                }
            }
            
            if (!exists) {
                mergedConnections.add(importedConn);
            }
        }
        
        log.info("Merged {} existing connections with {} imported connections, total: {}", 
                existingConnections.size(), importedConnections.size(), mergedConnections.size());
        
        return mergedConnections;
    }

    private static boolean isValidConnection(ConnectionParams connection) {
        boolean valid = connection.getHost() != null && !connection.getHost().isBlank()
                && connection.getUsername() != null && !connection.getUsername().isBlank()
                && connection.getPort() >= 1 && connection.getPort() <= 65535;
        if (!valid) {
            log.warn("Rejected invalid imported SSH connection record");
        }
        return valid;
    }
}
