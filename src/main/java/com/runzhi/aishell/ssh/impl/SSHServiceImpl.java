/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */


package com.runzhi.aishell.ssh.impl;

import com.jcraft.jsch.Session;
import com.runzhi.aishell.common.config.ConfigManager;
import com.runzhi.aishell.monitor.SystemMonitor;
import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.ssh.ConnectionStateListener;
import com.runzhi.aishell.ssh.FileTransferService;
import com.runzhi.aishell.ssh.SSHConnectionManager;
import com.runzhi.aishell.ssh.SSHService;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.domain.FileInfo;
import com.runzhi.aishell.ssh.factory.SSHConnectionFactory;
import javafx.collections.ObservableList;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH服务实现类 - 优化版本
 * 使用Lombok减少样板代码，使用工厂模式创建连接
 */
@Data
@Slf4j
public class SSHServiceImpl implements SSHService {

    private SystemMonitor systemMonitor;
    private final Map<String, FileTransferService> tabFileTransferServices = new ConcurrentHashMap<>();

    // 存储默认连接参数
    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKeyPath;

    // 存储每个Tab的SSH连接
    /** 关闭连接用的独立线程池：disconnect 在远端挂起时会阻塞，不能占用业务线程（2026-10-23 审计 P1-21）。 */
    private static final java.util.concurrent.ExecutorService SHUTDOWN_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "ssh-graceful-shutdown");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, SSHConnectionManager> tabConnections = new ConcurrentHashMap<>();

    // 存储每个Tab的连接参数
    private final Map<String, ConnectionParams> tabConnectionParams = new ConcurrentHashMap<>();

    // 保存失败原因，供 UI 判断是否需要提示重新输入凭据。
    private final Map<String, String> lastConnectionErrors = new ConcurrentHashMap<>();

    // 每个Tab的独立工作目录
    private final Map<String, String> tabWorkingDirectories = new ConcurrentHashMap<>();

    @Override
    public void disconnect() {
        // 断开所有Tab连接
        for (SSHConnectionManager connection : tabConnections.values()) {
            if (connection != null) {
                connection.disconnect();
            }
        }
        tabConnections.clear();
        tabWorkingDirectories.clear();
        tabConnectionParams.clear();

        disconnectAllFileTransferServices();
    }

    @Override
    public boolean isConnected() {
        // 检查是否有至少一个Tab连接处于连接状态
        return tabConnections.values().stream()
                .anyMatch(SSHConnectionManager::isConnected);
    }

    @Override
    public synchronized SSHConnectionManager createTabConnection(String tabId, String host, int port, String username, String password, String privateKeyPath) {
        SSHConnectionManager existing = tabConnections.get(tabId);
        if (existing != null && existing.isConnected()) {
            return existing;
        }
        if (existing != null) {
            tabConnections.remove(tabId, existing);
            removeFileTransferService(tabId);
            existing.disconnect();
        }

        // 保存此Tab的连接参数
        ConnectionParams params = new ConnectionParams();
        params.setHost(host);
        params.setPort(port);
        params.setUsername(username);
        params.setPassword(password);
        params.setPrivateKeyPath(privateKeyPath);

        log.debug("Creating tab connection: {} for {}@{}:{}", tabId,
                params.getUsername(), params.getHost(), params.getPort());

        tabConnectionParams.put(tabId, params);

        SSHConnectionManager tabConnection = SSHConnectionFactory.createConnection(params, tabId);
        registerReconnectSftpRecovery(tabId, tabConnection);

        try {
            // 注意：我们在这里不能添加监听器，因为我们没有MonitorPanelController的实例
            // 实际的监听器将在TerminalService中添加
            if (tabConnection.connect()) {
                lastConnectionErrors.remove(tabId);
                tabConnections.put(tabId, tabConnection);
                // 为新Tab设置默认目录
                tabWorkingDirectories.put(tabId, "~");

                // 启用自动重连功能
                tabConnection.setAutoReconnectEnabled(true);
                tabConnection.setMaxReconnectAttempts(5);
                tabConnection.setReconnectDelay(5000); // 5秒延迟

                // 【关键改动】移除在这里初始化系统监控器 - 避免阻塞 SSH 初始化
                // if (systemMonitor == null) {
                //     initializeSystemMonitor();
                // }

                registerFileTransferService(tabId, tabConnection);

                log.info("Successfully created tab connection: {}", tabId);
                return tabConnection;
            } else {
                lastConnectionErrors.put(tabId, "SSH connection failed");
                log.error("Failed to create tab connection: {} for host:{}:{}", tabId, host, port);
                // 连接失败，清理资源
                tabConnection.disconnect();
                return null;
            }
        } catch (Exception e) {
            lastConnectionErrors.put(tabId, getErrorMessage(e));
            log.error("Exception occurred while creating tab connection: {} for host:{}:{} - {}", tabId, host, port, e.getMessage(), e);
            // 连接失败，清理资源
            tabConnection.disconnect();
            return null;
        }
    }

    @Override
    public synchronized SSHConnectionManager createTabConnectionWithCallback(String tabId, String host, int port, String username, String password, String privateKeyPath, SSHConnectionManager.ShellOutputCallback callback) {
        SSHConnectionManager existing = tabConnections.get(tabId);
        if (existing != null && existing.isConnected()) {
            return existing;
        }
        if (existing != null) {
            tabConnections.remove(tabId, existing);
            removeFileTransferService(tabId);
            existing.disconnect();
        }

        // 保存此Tab的连接参数
        ConnectionParams params = new ConnectionParams();
        params.setHost(host);
        params.setPort(port);
        params.setUsername(username);
        params.setPassword(password);
        params.setPrivateKeyPath(privateKeyPath);

        tabConnectionParams.put(tabId, params);

        SSHConnectionManager tabConnection = SSHConnectionFactory.createConnection(params, tabId);
        registerReconnectSftpRecovery(tabId, tabConnection);

        // 【关键】在连接之前注册回调，确保不会丢失早期输出
        if (callback != null) {
            tabConnection.addOutputCallback(callback);
            log.info("Pre-registered output callback for tab: {}", tabId);
        }

        try {
            if (tabConnection.connect()) {
                lastConnectionErrors.remove(tabId);
                tabConnections.put(tabId, tabConnection);
                tabWorkingDirectories.put(tabId, "~");

                tabConnection.setAutoReconnectEnabled(true);
                tabConnection.setMaxReconnectAttempts(5);
                tabConnection.setReconnectDelay(5000);

                registerFileTransferService(tabId, tabConnection);

                log.info("Successfully created tab connection with callback: {}", tabId);
                return tabConnection;
            } else {
                lastConnectionErrors.put(tabId, "SSH connection failed");
                log.error("Failed to create tab connection: {} for host:{}:{}", tabId, host, port);
                tabConnection.disconnect();
                return null;
            }
        } catch (Exception e) {
            lastConnectionErrors.put(tabId, getErrorMessage(e));
            log.error("Exception occurred while creating tab connection: {} for host:{}:{} - {}", tabId, host, port, e.getMessage(), e);
            tabConnection.disconnect();
            return null;
        }
    }

    @Override
    public SSHConnectionManager getTabConnection(String tabId) {
        return tabConnections.get(tabId);
    }

    @Override
    public String getLastConnectionError(String tabId) {
        return lastConnectionErrors.get(tabId);
    }

    private String getErrorMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                if (!message.isEmpty()) {
                    message.append(" | ");
                }
                message.append(current.getMessage());
            }
            current = current.getCause();
        }
        return message.toString();
    }

    @Override
    public void removeTabConnection(String tabId) {
        removeFileTransferService(tabId);
        SSHConnectionManager connection = tabConnections.remove(tabId);
        if (connection != null) {
            connection.disconnect();
        }
        tabWorkingDirectories.remove(tabId);
        tabConnectionParams.remove(tabId);
    }

    @Override
    public SystemMetrics getSystemMetrics() {
        return systemMonitor != null ? systemMonitor.getSystemMetrics() : null;
    }

    @Override
    public void startMonitoring() {
        if (systemMonitor != null) {
            systemMonitor.startMonitoring();
        }
    }

    @Override
    public void stopMonitoring() {
        if (systemMonitor != null) {
            systemMonitor.stopMonitoring();
        }
    }

    @Override
    public String getCurrentWorkingDirectoryForTab(String tabId) {
        String tabDir = tabWorkingDirectories.get(tabId);
        return tabDir != null ? tabDir : "~"; // 如果没有为Tab设置目录，返回默认目录
    }

    @Override
    public void setCurrentWorkingDirectoryForTab(String tabId, String directory) {
        tabWorkingDirectories.put(tabId, directory);
    }

    @Override
    public void handleCdCommandForTab(String tabId, String command) {
        // 裸 "cd" 长度只有 2，substring(3) 会抛 StringIndexOutOfBoundsException（2026-10-23 审计 P1-20）
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.equals("cd")) {
            setCurrentWorkingDirectoryForTab(tabId, "~");
            return;
        }
        String path = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
        if (path.isEmpty()) path = "~";

        String currentDir = getCurrentWorkingDirectoryForTab(tabId);
        String newDir = normalizePath(path.startsWith("/") ? path : currentDir + "/" + path);  // 只本地计算新目录
        setCurrentWorkingDirectoryForTab(tabId, newDir);  // 更新本地
    }

    /**
     * 规范化路径，处理 .. 和 .
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        String[] parts = path.split("/");
        java.util.List<String> normalizedParts = new java.util.ArrayList<>();

        for (String part : parts) {
            if (part.equals("..")) {
                if (!normalizedParts.isEmpty() && !normalizedParts.getLast().equals("..")) {
                    normalizedParts.removeLast();
                } else if (!path.startsWith("/")) {
                    // 如果路径是相对路径且尝试向上超出当前目录，则保留 ..
                    normalizedParts.add("..");
                }
            } else if (!part.isEmpty() && !part.equals(".")) {
                normalizedParts.add(part);
            }
        }

        StringBuilder result = new StringBuilder();
        if (path.startsWith("/")) {
            result.append("/"); // 保留绝对路径的开头斜杠
        }

        for (int i = 0; i < normalizedParts.size(); i++) {
            if (i > 0 || !path.startsWith("/")) {
                result.append("/");
            }
            result.append(normalizedParts.get(i));
        }

        if (result.isEmpty()) {
            return path.startsWith("/") ? "/" : ".";
        }

        return result.toString();
    }

    @Override
    public void syncRemoteDirectoryForTab(String tabId) {
        SSHConnectionManager tabConnection = tabConnections.get(tabId);
        if (tabConnection == null) {
            return; // Tab连接不存在
        }

        try {
            if (tabConnection.isConnected()) {
                String remoteDir = tabConnection.executeCommand("pwd").trim();
                if (!remoteDir.isEmpty()) {
                    setCurrentWorkingDirectoryForTab(tabId, remoteDir);
                }
            }
        } catch (Exception e) {
            log.error("Error syncing remote directory for tab: {}", tabId, e);
        }
    }

    @Override
    public ObservableList<FileInfo> listFilesForTab(String tabId, String path) throws Exception {
        return requireFileTransferService(tabId).listFiles(path);
    }

    @Override
    public String readTextFileForTab(String tabId, String path, int maxBytes) throws Exception {
        return requireFileTransferService(tabId).readTextFile(path, maxBytes);
    }

    @Override
    public void writeTextFileForTab(String tabId, String path, String content) throws Exception {
        requireFileTransferService(tabId).writeTextFile(path, content);
    }

    @Override
    public void renamePathForTab(String tabId, String from, String to) throws Exception {
        requireFileTransferService(tabId).rename(from, to);
    }

    @Override
    public void uploadFilesForTab(String tabId, List<String> localFilePaths, String remoteDirPath,
                                  FileTransferService.ProgressCallback callback) throws Exception {
        requireFileTransferService(tabId).uploadFiles(localFilePaths, remoteDirPath, callback);
    }

    @Override
    public void uploadFilesForTab(String tabId, List<String> localFilePaths, String remoteDirPath,
                                  FileTransferService.ProgressCallback callback,
                                  FileTransferService.OverwritePolicy overwritePolicy,
                                  FileTransferService.TransferControl control) throws Exception {
        requireFileTransferService(tabId).uploadFiles(localFilePaths, remoteDirPath, callback, overwritePolicy, control);
    }

    @Override
    public void downloadFilesForTab(String tabId, List<String> remoteFilePaths, String localDirPath,
                                    FileTransferService.ProgressCallback callback) throws Exception {
        requireFileTransferService(tabId).downloadFiles(remoteFilePaths, localDirPath, callback);
    }

    @Override
    public void downloadFilesForTab(String tabId, List<String> remoteFilePaths, String localDirPath,
                                    FileTransferService.ProgressCallback callback,
                                    FileTransferService.OverwritePolicy overwritePolicy,
                                    FileTransferService.TransferControl control) throws Exception {
        requireFileTransferService(tabId).downloadFiles(remoteFilePaths, localDirPath, callback, overwritePolicy, control);
    }

    @Override
    public boolean remotePathExistsForTab(String tabId, String path) throws Exception {
        return requireFileTransferService(tabId).exists(path);
    }

    @Override
    public int openLocalPortForward(String tabId, String bindAddress, int localPort,
                                    String remoteHost, int remotePort) throws Exception {
        return requireTabConnection(tabId).openLocalPortForward(bindAddress, localPort, remoteHost, remotePort);
    }

    @Override
    public void closeLocalPortForward(String tabId, String bindAddress, int localPort) throws Exception {
        requireTabConnection(tabId).closeLocalPortForward(bindAddress, localPort);
    }

    @Override
    public void openRemotePortForward(String tabId, int remotePort, String localHost, int localPort)
            throws Exception {
        requireTabConnection(tabId).openRemotePortForward(remotePort, localHost, localPort);
    }

    @Override
    public void closeRemotePortForward(String tabId, int remotePort) throws Exception {
        requireTabConnection(tabId).closeRemotePortForward(remotePort);
    }

    @Override
    public int openDynamicPortForward(String tabId, String bindAddress, int localPort) throws Exception {
        return requireTabConnection(tabId).openDynamicPortForward(bindAddress, localPort);
    }

    @Override
    public void closeDynamicPortForward(String tabId, String bindAddress, int localPort) throws Exception {
        requireTabConnection(tabId).closeDynamicPortForward(bindAddress, localPort);
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getHostForTab(String tabId) {
        ConnectionParams params = tabConnectionParams.get(tabId);
        return params == null ? null : params.getHost();
    }

    @Override
    public String getUsernameForTab(String tabId) {
        ConnectionParams params = tabConnectionParams.get(tabId);
        return params == null ? null : params.getUsername();
    }

    // 连接配置管理相关方法
    @Override
    public void saveConnectionProfile(String profileName, String host, int port, String username, String password, String privateKeyPath) {
        // 将连接配置保存到持久化存储
        ConnectionParams params = new ConnectionParams();
        params.setProfileName(profileName);
        params.setHost(host);
        params.setPort(port);
        params.setUsername(username);
        params.setPassword(password);
        params.setPrivateKeyPath(privateKeyPath);
        ConfigManager.getInstance().saveConnectionConfig(params);
    }

    @Override
    public ConnectionParams getConnectionProfile(String profileName) {
        // 从持久化存储加载连接配置
        List<ConnectionParams> connections = ConfigManager.getInstance().loadConnectionConfigs();
        return connections.stream()
                // 统一以 profileName（回退显示名）匹配，同时保留 host:port 与 username 兼容旧入口
                .filter(conn -> matchesProfile(conn, profileName))
                .findFirst()
                .orElse(null);
    }

    /** 档案匹配：profileName、显示名、host:port、username 四种口径兼容。 */
    private boolean matchesProfile(ConnectionParams conn, String profileName) {
        if (profileName == null) return false;
        if (conn.getProfileName() != null && conn.getProfileName().equals(profileName)) return true;
        if (conn.getUsername() != null && conn.getUsername().equals(profileName)) return true;
        if ((conn.getHost() + ":" + conn.getPort()).equals(profileName)) return true;
        return SSHConnectionManager.buildDisplayName(conn).equals(profileName);
    }
    
    public Map<String, ConnectionParams> getAllConnectionProfiles() {
        // 从持久化存储加载所有连接配置
        Map<String, ConnectionParams> profiles = new ConcurrentHashMap<>();
        List<ConnectionParams> connections = ConfigManager.getInstance().loadConnectionConfigs();

        for (ConnectionParams params : connections) {
            // key 统一使用 profileName（无名称时回退显示名），与 getConnectionProfile 的匹配口径一致，
            // 否则档案下拉框选中后表单从不自动填充、removeConnectionProfile 永远删不掉（2026-10-23 审计 P1-5）
            String displayName = (params.getProfileName() != null && !params.getProfileName().isBlank())
                    ? params.getProfileName()
                    : params.getHost() + ":" + params.getPort() + " - " + params.getUsername();
            profiles.put(displayName, params);
        }

        return profiles;
    }

    @Override
    public void removeConnectionProfile(String profileName) {
        // 从持久化存储中移除连接配置
        List<ConnectionParams> connections = ConfigManager.getInstance().loadConnectionConfigs();
        // 统一匹配口径，避免按用户名误删无关条目（2026-10-23 审计 P1-5）
        connections.removeIf(conn -> matchesProfile(conn, profileName));
        ConfigManager.getInstance().saveConnectionConfigs(connections);
    }

    @Override
    public ConnectionParams getTabConnectionParams(String tabId) {
        return tabConnectionParams.get(tabId);
    }

    @Override
    public void checkConnectionHealth(String tabId) {
        SSHConnectionManager connection = getTabConnection(tabId);
        if (connection != null) {
            connection.checkConnectionHealth();
        }
    }

    @Override
    public boolean isConnectionHealthy(String tabId) {
        SSHConnectionManager connection = getTabConnection(tabId);
        if (connection != null) {
            return connection.isConnected() && connection.isConnectionValid();
        }
        return false;
    }

    @Override
    public void enableAutoReconnect(String tabId) {
        SSHConnectionManager connection = getTabConnection(tabId);
        if (connection != null) {
            connection.setAutoReconnectEnabled(true);
        }
    }

    @Override
    public void disableAutoReconnect(String tabId) {
        SSHConnectionManager connection = getTabConnection(tabId);
        if (connection != null) {
            connection.setAutoReconnectEnabled(false);
        }
    }

    @Override
    public void setMaxReconnectAttempts(String tabId, int maxAttempts) {
        SSHConnectionManager connection = getTabConnection(tabId);
        if (connection != null) {
            connection.setMaxReconnectAttempts(maxAttempts);
        }
    }

    @Override
    public void setReconnectDelay(String tabId, long delayMs) {
        SSHConnectionManager connection = getTabConnection(tabId);
        if (connection != null) {
            connection.setReconnectDelay(delayMs);
        }
    }

    @Override
    public int getActiveConnectionCount() {
        return (int) tabConnections.values().stream()
                .filter(SSHConnectionManager::isConnected)
                .count();
    }

    @Override
    public Map<String, String> getAllConnectionStatus() {
        Map<String, String> statusMap = new ConcurrentHashMap<>();
        for (Map.Entry<String, SSHConnectionManager> entry : tabConnections.entrySet()) {
            SSHConnectionManager connection = entry.getValue();
            if (connection != null) {
                String status = connection.isConnected() ? "Connected" : "Disconnected";
                if (connection.isConnected()) {
                    status += " (Valid: " + connection.isConnectionValid() + ")";
                }
                statusMap.put(entry.getKey(), status);
            }
        }
        return statusMap;
    }

    @Override
    public void gracefulShutdown(long timeoutMs) {
        log.info("Starting graceful shutdown of SSH service...");

        // 停止监控
        if (systemMonitor != null) {
            systemMonitor.stopMonitoring();
        }

        // timeoutMs 此前被完全忽略：远端 TCP 挂起时 disconnect() 会无限期阻塞关闭流程（2026-10-23 审计 P1-21）
        java.util.List<java.util.concurrent.Future<?>> pending = new java.util.ArrayList<>();
        for (Map.Entry<String, SSHConnectionManager> entry : tabConnections.entrySet()) {
            SSHConnectionManager connection = entry.getValue();
            if (connection != null) {
                pending.add(SHUTDOWN_POOL.submit(() -> {
                    try { connection.disconnect(); } catch (Exception e) {
                        log.warn("关闭连接时出错: {}", e.getMessage());
                    }
                }));
            }
        }
        tabConnections.clear();
        tabWorkingDirectories.clear();
        tabConnectionParams.clear();

        // 在预算内等待各连接断开完成，超时不再阻塞
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        for (java.util.concurrent.Future<?> task : pending) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                task.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) { }
        }
        disconnectAllFileTransferServices();

        log.info("SSH service gracefully shut down");
    }

    @Override
    public void createDirectoryForTab(String tabId, String directoryPath) throws Exception {
        requireFileTransferService(tabId).createDirectory(directoryPath);
    }

    @Override
    public void deletePathForTab(String tabId, String path, boolean directory) throws Exception {
        FileTransferService service = requireFileTransferService(tabId);
        if (directory) service.deleteDirectory(path);
        else service.deleteFile(path);
    }

    /**
     * 自动重连成功后，旧的 SFTP 服务仍绑定在已断开的会话上，文件操作会静默失败（假活）。
     * 监听连接恢复事件并重建该标签的 SFTP 服务。初始连接时该标签尚未登记到
     * {@link #tabConnections}（监听器在 connect() 内部触发），因此重建只在真正的重连场景执行。
     */
    private void registerReconnectSftpRecovery(String tabId, SSHConnectionManager connection) {
        connection.addConnectionStateListener(new ConnectionStateListener() {
            @Override
            public void onConnected(String id) {
                if (tabConnections.get(tabId) == connection) {
                    log.info("Rebuilding SFTP service for tab {} after reconnection", tabId);
                    registerFileTransferService(tabId, connection);
                }
            }
            @Override
            public void onDisconnected(String id) { }
        });
    }

    private void registerFileTransferService(String tabId, SSHConnectionManager connection) {
        Session session = connection.getSession();
        if (session != null) {
            removeFileTransferService(tabId);
            tabFileTransferServices.put(tabId, new FileTransferService(session));
        }
    }

    private FileTransferService requireFileTransferService(String tabId) {
        SSHConnectionManager connection = tabConnections.get(tabId);
        FileTransferService service = tabFileTransferServices.get(tabId);
        if (connection == null || !connection.isConnected() || service == null) {
            throw new IllegalStateException("Tab " + tabId + " is not connected for file transfer");
        }
        return service;
    }

    private SSHConnectionManager requireTabConnection(String tabId) {
        SSHConnectionManager connection = tabConnections.get(tabId);
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("Tab " + tabId + " is not connected");
        }
        return connection;
    }

    private void removeFileTransferService(String tabId) {
        FileTransferService service = tabFileTransferServices.remove(tabId);
        if (service != null) service.disconnect();
    }

    private void disconnectAllFileTransferServices() {
        tabFileTransferServices.values().forEach(FileTransferService::disconnect);
        tabFileTransferServices.clear();
    }

}
