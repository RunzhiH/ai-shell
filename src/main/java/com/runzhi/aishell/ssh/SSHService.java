/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.runzhi.aishell.monitor.domain.SystemMetrics;
import com.runzhi.aishell.ssh.domain.ConnectionParams;
import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.collections.ObservableList;

import java.util.List;

public interface SSHService {

    /** Resolve a browser path on the SFTP worker; default keeps test/third-party services compatible. */
    default String canonicalDirectoryForTab(String tabId,String path) throws Exception { return path; }

    /**
     * 断开所有标签连接
     */
    void disconnect();

    /**
     * 检查是否至少有一个标签已连接
     */
    boolean isConnected();

    /**
     * 为指定Tab创建独立的SSH连接（使用指定参数）
     */
    SSHConnectionManager createTabConnection(String tabId, String host, int port, String username, String password, String privateKeyPath);

    /**
     * 为指定Tab创建独立的SSH连接，并在连接前注册回调
     * 解决早期输出丢失的问题
     */
    SSHConnectionManager createTabConnectionWithCallback(String tabId, String host, int port, String username, String password, String privateKeyPath, SSHConnectionManager.ShellOutputCallback callback);

    /**
     * 获取指定Tab的SSH连接
     */
    SSHConnectionManager getTabConnection(String tabId);

    /**
     * 移除指定Tab的SSH连接
     */
    void removeTabConnection(String tabId);

    /**
     * 获取系统指标
     */
    SystemMetrics getSystemMetrics();

    /**
     * 启动系统监控
     */
    void startMonitoring();

    /**
     * 停止系统监控
     */
    void stopMonitoring();

    /**
     * 获取指定Tab的工作目录
     */
    String getCurrentWorkingDirectoryForTab(String tabId);

    /**
     * 设置指定Tab的工作目录
     */
    void setCurrentWorkingDirectoryForTab(String tabId, String directory);

    /**
     * 处理指定Tab的cd命令
     */
    void handleCdCommandForTab(String tabId, String command);

    /**
     * 同步指定Tab的远程目录
     */
    void syncRemoteDirectoryForTab(String tabId);

    ObservableList<FileInfo> listFilesForTab(String tabId, String path) throws Exception;

    String readTextFileForTab(String tabId, String path, int maxBytes) throws Exception;

    void writeTextFileForTab(String tabId, String path, String content) throws Exception;

    void renamePathForTab(String tabId, String from, String to) throws Exception;

    /**
     * 上传文件
     */
    void uploadFilesForTab(String tabId, List<String> localFilePaths, String remoteDirPath,
                           FileTransferService.ProgressCallback callback) throws Exception;

    void uploadFilesForTab(String tabId, List<String> localFilePaths, String remoteDirPath,
                           FileTransferService.ProgressCallback callback,
                           FileTransferService.OverwritePolicy overwritePolicy,
                           FileTransferService.TransferControl control) throws Exception;

    /**
     * 打包上传：把本地目录压成 zip 上传到远端并解压，最后清理压缩包。
     *
     * @param remoteDirPath 解压目标父目录（压缩包会解压到其下的同名目录）
     * @return true 表示成功解压；false 表示已上传但远端没有可用解压工具
     */
    boolean uploadPackedFolderForTab(String tabId, String localFolderPath, String remoteDirPath,
                                     FileTransferService.ProgressCallback callback,
                                     FileTransferService.OverwritePolicy overwritePolicy,
                                     FileTransferService.TransferControl control) throws Exception;

    /**
     * 下载文件
     */
    void downloadFilesForTab(String tabId, List<String> remoteFilePaths, String localDirPath,
                             FileTransferService.ProgressCallback callback) throws Exception;

    void downloadFilesForTab(String tabId, List<String> remoteFilePaths, String localDirPath,
                             FileTransferService.ProgressCallback callback,
                             FileTransferService.OverwritePolicy overwritePolicy,
                             FileTransferService.TransferControl control) throws Exception;

    boolean remotePathExistsForTab(String tabId, String path) throws Exception;

    int openLocalPortForward(String tabId, String bindAddress, int localPort,
                             String remoteHost, int remotePort) throws Exception;

    void closeLocalPortForward(String tabId, String bindAddress, int localPort) throws Exception;

    void openRemotePortForward(String tabId, int remotePort, String localHost, int localPort) throws Exception;

    void closeRemotePortForward(String tabId, int remotePort) throws Exception;

    int openDynamicPortForward(String tabId, String bindAddress, int localPort) throws Exception;

    void closeDynamicPortForward(String tabId, String bindAddress, int localPort) throws Exception;

    /**
     * 获取主机名
     */
    String getHost();

    /**
     * 获取用户名
     */
    String getUsername();

    String getHostForTab(String tabId);

    String getUsernameForTab(String tabId);

    void createDirectoryForTab(String tabId, String directoryPath) throws Exception;

    void deletePathForTab(String tabId, String path, boolean directory) throws Exception;

    /**
     * 检查指定Tab的连接健康状态
     */
    void checkConnectionHealth(String tabId);
    
    /**
     * 获取指定Tab的连接健康状态
     */
    boolean isConnectionHealthy(String tabId);
    
    /**
     * 为指定Tab启用自动重连
     */
    void enableAutoReconnect(String tabId);
    
    /**
     * 为指定Tab禁用自动重连
     */
    void disableAutoReconnect(String tabId);
    
    /**
     * 为指定Tab设置最大重连尝试次数
     */
    void setMaxReconnectAttempts(String tabId, int maxAttempts);
    
    /**
     * 为指定Tab设置重连延迟时间
     */
    void setReconnectDelay(String tabId, long delayMs);

    // 连接配置管理相关方法
    void saveConnectionProfile(String profileName, String host, int port, String username, String password, String privateKeyPath);

    ConnectionParams getConnectionProfile(String profileName);

    java.util.Map<String, ConnectionParams> getAllConnectionProfiles();

    void removeConnectionProfile(String profileName);

    /**
     * 获取指定Tab的连接参数
     */
    ConnectionParams getTabConnectionParams(String tabId);

    /**
     * 获取指定 Tab 最近一次连接失败原因；成功连接后返回 null。
     */
    String getLastConnectionError(String tabId);

    /**
     * 获取当前活动连接的数量
     */
    int getActiveConnectionCount();
    
    /**
     * 获取所有连接的状态信息
     */
    java.util.Map<String, String> getAllConnectionStatus();
    
    /**
     * 优雅地关闭服务，等待所有连接正常断开
     */
    void gracefulShutdown(long timeoutMs);

    /**
     * 某个保存的连接配置当前有多少个会话仍处于已连接状态。
     * 同一配置允许打开多个标签；只要至少一个在线，就表示该配置存在活动连接。
     */
    default int activeSessionCount(ConnectionParams params) {
        return 0;
    }

    /**
     * 订阅会话状态变化（连接建立、失败、断开、标签关闭）。
     * 视图据此刷新状态，避免各自轮询或维护副本。
     */
    default void addSessionStateListener(Runnable listener) {
    }

    default void removeSessionStateListener(Runnable listener) {
    }
}
