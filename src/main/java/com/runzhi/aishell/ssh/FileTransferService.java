/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.*;
import com.runzhi.aishell.ssh.domain.FileInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件传输服务类，提供SFTP文件操作功能
 */
@Slf4j
public class FileTransferService {
    private Session session;
    private final boolean ownsSession;
    protected ChannelSftp sftpChannel;
    // ChannelSftp 非线程安全：多线程共用同一通道会使 SFTP 响应错乱，
    // 表现为 IndexOutOfBoundsException: Range [0, 0 + <垃圾值>) out of bounds
    // 或 SSH_FX_FAILURE。所有通道操作必须串行
    private final Object channelLock = new Object();
    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKeyPath;

    public FileTransferService(Session session) {
        this.session = session;
        this.ownsSession = false;
    }

    // 添加支持独立连接参数的构造函数
    public FileTransferService(String host, int port, String username, String password, String privateKeyPath) {
        this.ownsSession = true;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPath = privateKeyPath;
    }

    /**
     * 连接到SFTP
     */
    public void connect() throws JSchException {
        synchronized (channelLock) {
            if (session != null && session.isConnected()) {
                // 如果已经通过Session构造，则使用现有Session
                if (sftpChannel == null || !sftpChannel.isConnected()) {
                    sftpChannel = (ChannelSftp) session.openChannel("sftp");
                    sftpChannel.connect();
                }
            } else {
                // 如果通过连接参数构造，则建立新连接
                connectWithParams();
            }
        }
    }

    /**
     * 使用连接参数建立SFTP连接
     */
    public void connectWithParams() throws JSchException {
        synchronized (channelLock) {
            if (sftpChannel == null || !sftpChannel.isConnected()) {
            JSch jsch = new JSch();

            // 如果提供了私钥路径，则使用密钥认证
            if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                java.io.File keyFile = new java.io.File(privateKeyPath);
                if (!keyFile.exists()) {
                    throw new JSchException("Private key file does not exist: " + privateKeyPath);
                }
                jsch.addIdentity(privateKeyPath, (byte[]) null);
            }

            Session tempSession = jsch.getSession(username, host, port);
            tempSession.setUserInfo(new FxHostKeyUserInfo(password));

            // 如果有密码，则设置密码
            if (password != null && !password.isEmpty()) {
                tempSession.setPassword(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
            try {
                Files.createDirectories(knownHosts.getParent());
                if (!Files.exists(knownHosts)) {
                    Files.createFile(knownHosts);
                }
            } catch (java.io.IOException e) {
                throw new JSchException("无法创建 SSH known_hosts 文件: " + knownHosts, e);
            }
            jsch.setKnownHosts(knownHosts.toString());

            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "ask");
            config.put("PreferredAuthentications", "publickey,password");
            tempSession.setConfig(config);

            try {
                tempSession.connect(30000); // 设置30秒超时

                // 保存独立创建的 Session，确保 disconnect() 能释放底层连接。
                session = tempSession;
                sftpChannel = (ChannelSftp) tempSession.openChannel("sftp");
                sftpChannel.connect();
            } catch (JSchException e) {
                tempSession.disconnect();
                session = null;
                throw e;
            }
            }
        }
    }

    /**
     * 断开SFTP连接
     */
    public void disconnect() {
        synchronized (channelLock) {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
            if (ownsSession && session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * 获取文件列表
     *
     * @param path 路径
     * @return 文件信息列表
     */
    public ObservableList<FileInfo> listFiles(String path) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
            ObservableList<FileInfo> files = FXCollections.observableArrayList();

            Vector<ChannelSftp.LsEntry> entries = sftpChannel.ls(path);
            for (ChannelSftp.LsEntry entry : entries) {
                // 跳过当前目录和父目录
                if (!entry.getFilename().equals(".") && !entry.getFilename().equals("..")) {
                    // 判断文件类型
                    String fileType = "FILE";
                    if (entry.getAttrs().isDir()) {
                        fileType = "DIR";
                    }

                    FileInfo fileInfo = new FileInfo(
                            entry.getFilename(),
                            formatFileSize(entry.getAttrs().getSize()),
                            fileType,
                            entry.getAttrs().getPermissions(),
                            (long) entry.getAttrs().getMTime()
                    );
                    files.add(fileInfo);
                }
            }

            return files;
        }
    }

    /**
     * 创建目录
     * 
     * @param directoryPath 要创建的目录路径
     */
    public void createDirectory(String directoryPath) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }

            try {
                sftpChannel.mkdir(directoryPath);
                log.info("Directory created: {}", directoryPath);
            } catch (SftpException e) {
                log.error("Failed to create directory: {}", directoryPath, e);
                throw e;
            }
        }
    }

    /**
     * 删除文件
     * 
     * @param filePath 要删除的文件路径
     */
    public void deleteFile(String filePath) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }

            try {
                sftpChannel.rm(filePath);
                log.info("File deleted: {}", filePath);
            } catch (SftpException e) {
                log.error("Failed to delete file: {}", filePath, e);
                throw e;
            }
        }
    }

    /**
     * 删除目录
     * 
     * @param directoryPath 要删除的目录路径
     */
    public void deleteDirectory(String directoryPath) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }

            try {
                sftpChannel.rmdir(directoryPath);
                log.info("Directory deleted: {}", directoryPath);
            } catch (SftpException e) {
                log.error("Failed to delete directory: {}", directoryPath, e);
                throw e;
            }
        }
    }

    /**
     * 重命名文件或目录
     * 
     * @param oldPath 原路径
     * @param newPath 新路径
     */
    public void rename(String oldPath, String newPath) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }

            try {
                sftpChannel.rename(oldPath, newPath);
                log.info("Renamed: {} to {}", oldPath, newPath);
            } catch (SftpException e) {
                log.error("Failed to rename: {} to {}", oldPath, newPath, e);
                throw e;
            }
        }
    }

    /**
     * 检查路径是否存在
     * 
     * @param path 要检查的路径
     * @return 是否存在
     */
    public boolean exists(String path) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
                sftpChannel.stat(path);
                return true;
            } catch (SftpException e) {
                // 2 = SSH_FX_NO_SUCH_FILE：只有"不存在"才返回 false，其余通道异常向上抛
                // 此前吞掉所有异常，通道半断时上层会把"无法访问"当成"不存在"继续删除/覆盖（2026-10-23 审计 P1-27）
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
                throw e;
            } catch (Exception e) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "exists() 失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 获取文件属性
     * 
     * @param path 文件路径
     * @return 文件属性
     */
    public ChannelSftp.LsEntry getFileEntry(String path) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
                int slash = path.lastIndexOf('/');
                if (slash < 0) throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "Invalid path: " + path);
                String dir = slash == 0 ? "/" : path.substring(0, slash);
                String filename = path.substring(slash + 1);

                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> entries = sftpChannel.ls(dir);
                for (ChannelSftp.LsEntry entry : entries) {
                    if (entry.getFilename().equals(filename)) {
                        return entry;
                    }
                }
                return null;
            } catch (Exception e) {
                log.error("Failed to get file entry: {}", path, e);
                throw new SftpException(4, "Error getting file entry: " + e.getMessage());
            }
        }
    }

    // 传输进度回调接口
    public interface ProgressCallback {
        void onProgress(String fileName, long current, long total);

        void onError(String fileName, String errorMessage);

        void onComplete(String fileName);
    }

    /** Reads a small UTF-8 remote text file without invoking a shell command. */
    public String readTextFile(String remotePath, int maxBytes) throws JSchException, SftpException, java.io.IOException {
        if (remotePath == null || remotePath.isBlank() || remotePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid remote path is required");
        }
        if (maxBytes < 1 || maxBytes > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid remote read limit");
        }
        synchronized (channelLock) {
            connect();
            SftpATTRS attributes = sftpChannel.lstat(remotePath);
            if (attributes.isDir()) throw new IllegalArgumentException("Remote path is a directory");
            if (attributes.getSize() > maxBytes) throw new IllegalArgumentException("Remote file exceeds the read limit");
            try (InputStream input = sftpChannel.get(remotePath);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxBytes) throw new IllegalArgumentException("Remote file exceeds the read limit");
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        }
    }

    /** Writes small UTF-8 text to a remote file over SFTP without invoking a shell command. */
    public void writeTextFile(String remotePath, String content)
            throws JSchException, SftpException, java.io.IOException {
        if (remotePath == null || remotePath.isBlank() || remotePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A valid remote path is required");
        }
        if (content == null || content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid text content");
        }
        synchronized (channelLock) {
            connect();
            try (InputStream input = new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                sftpChannel.put(input, remotePath);
            }
        }
    }

    public enum OverwritePolicy { OVERWRITE, SKIP, RESUME }

    public static final class TransferControl {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    }

    /**
     * 批量上传文件
     *
     * @param localFilePaths 本地文件路径列表
     * @param remoteDirPath  远程目录路径
     */
    public void uploadFiles(List<String> localFilePaths, String remoteDirPath) throws SftpException {
        uploadFiles(localFilePaths, remoteDirPath, null);
    }

    /**
     * 批量上传文件，带进度回调
     *
     * @param localFilePaths 本地文件路径列表
     * @param remoteDirPath  远程目录路径
     * @param callback       进度回调
     */
    public void uploadFiles(List<String> localFilePaths, String remoteDirPath, ProgressCallback callback) throws SftpException {
        uploadFiles(localFilePaths, remoteDirPath, callback, OverwritePolicy.OVERWRITE, new TransferControl());
    }

    /**
     * 批量上传文件，带进度回调。列表中的目录会递归上传：远程按相对路径逐级建目录，
     * 进度回调按相对路径（如 assets/index.css）逐文件上报。
     */
    public void uploadFiles(List<String> localFilePaths, String remoteDirPath, ProgressCallback callback,
                            OverwritePolicy overwritePolicy, TransferControl control) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                if (callback != null) {
                    for (String filePath : localFilePaths) {
                        callback.onError(filePath, e.getMessage());
                    }
                }
                throw new RuntimeException(e);
            }

        // 单个文件失败时记录下来继续传后面的，最后统一汇报失败清单
        List<String> failedFiles = new java.util.ArrayList<>();
        for (String localFilePath : localFilePaths) {
            if (control.isCancelled()) throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "传输已取消");
            java.io.File localFile = new java.io.File(localFilePath);
            if (!localFile.exists()) {
                failedFiles.add("本地文件不存在: " + localFilePath);
                continue;
            }
            if (localFile.isDirectory()) {
                // 目录递归上传：远程按相对路径逐级建目录
                uploadDirectory(localFile, localFile.getName(), remoteDirPath,
                        callback, overwritePolicy, control, failedFiles);
            } else {
                uploadSingleFile(localFile, localFile.getName(), remoteDirPath,
                        callback, overwritePolicy, control, failedFiles);
            }
        }
        if (!failedFiles.isEmpty()) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE,
                    "以下文件上传失败：\n" + String.join("\n", failedFiles));
        }
        }
    }

    /**
     * 递归上传本地目录：远程按相对路径逐级 mkdir，子文件复用单文件上传逻辑。
     * 进度回调使用相对路径（如 assets/index.css）作为文件名，便于定位层级。
     */
    private void uploadDirectory(java.io.File localDir, String relativePath, String remoteDirPath,
                                 ProgressCallback callback, OverwritePolicy overwritePolicy,
                                 TransferControl control, List<String> failedFiles) throws SftpException {
        ensureRemoteDirectory(remoteDirPath + "/" + relativePath);
        java.io.File[] children = localDir.listFiles();
        if (children == null) {
            failedFiles.add(localDir.getPath() + "（无法读取目录内容）");
            return;
        }
        for (java.io.File child : children) {
            if (control.isCancelled()) throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "传输已取消");
            String childRelative = relativePath + "/" + child.getName();
            if (child.isDirectory()) {
                uploadDirectory(child, childRelative, remoteDirPath, callback, overwritePolicy, control, failedFiles);
            } else {
                uploadSingleFile(child, childRelative, remoteDirPath, callback, overwritePolicy, control, failedFiles);
            }
        }
    }

    /** 远程目录不存在时逐级创建；已存在则跳过，规避 mkdir 与 stat 的竞态误报。 */
    private void ensureRemoteDirectory(String remotePath) throws SftpException {
        if (exists(remotePath)) return;
        int slash = remotePath.lastIndexOf('/');
        if (slash > 0) ensureRemoteDirectory(remotePath.substring(0, slash));
        try {
            sftpChannel.mkdir(remotePath);
        } catch (SftpException mkdirFailed) {
            if (!exists(remotePath)) throw mkdirFailed;
        }
    }

    /** 上传单个本地文件：覆盖策略、断点续传与批量入口一致。 */
    private void uploadSingleFile(java.io.File localFile, String displayName, String remoteDirPath,
                                  ProgressCallback callback, OverwritePolicy overwritePolicy,
                                  TransferControl control, List<String> failedFiles) throws SftpException {
        String remoteFilePath = remoteDirPath + "/" + displayName;
        try {
            if (overwritePolicy == OverwritePolicy.SKIP && exists(remoteFilePath)) return;
            long fileSize = localFile.length();
            long remoteSize = -1;
            if (overwritePolicy == OverwritePolicy.RESUME && exists(remoteFilePath)) {
                remoteSize = sftpChannel.lstat(remoteFilePath).getSize();
                if (remoteSize == fileSize) {
                    if (callback != null) callback.onComplete(displayName);
                    return;
                }
            }
            int transferMode = overwritePolicy == OverwritePolicy.RESUME
                    && remoteSize > 0 && remoteSize < fileSize ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE;
            long initial = transferMode == ChannelSftp.RESUME ? remoteSize : 0;

            if (callback != null) {
                sftpChannel.put(localFile.getAbsolutePath(), remoteFilePath, new com.jcraft.jsch.SftpProgressMonitor() {
                    private long total = fileSize;
                    private long current = initial;

                    @Override
                    public void init(int op, String src, String dest, long max) {
                        // 初始化操作
                    }

                    @Override
                    public boolean count(long count) {
                        current += count;
                        if (callback != null) {
                            callback.onProgress(displayName, current, total);
                        }
                        return !control.isCancelled();
                    }

                    @Override
                    public void end() {
                        if (callback != null) {
                            callback.onComplete(displayName);
                        }
                    }
                }, transferMode);
            } else {
                sftpChannel.put(localFile.getAbsolutePath(), remoteFilePath, null, transferMode);
            }
        } catch (SftpException e) {
            if (callback != null) {
                callback.onError(localFile.getPath(), e.getMessage());
            }
            // 单个文件失败不中断整批传输：记录后继续，最后统一汇报
            failedFiles.add(localFile.getPath() + "（" + e.getMessage() + "）");
        }
    }

    /**
     * 批量下载文件
     *
     * @param remoteFilePaths 远程文件路径列表
     * @param localDirPath    本地目录路径
     */
    public void downloadFiles(List<String> remoteFilePaths, String localDirPath) throws SftpException {
        downloadFiles(remoteFilePaths, localDirPath, null);
    }

    /**
     * 批量下载文件，带进度回调
     *
     * @param remoteFilePaths 远程文件路径列表
     * @param localDirPath    本地目录路径
     * @param callback        进度回调
     */
    public void downloadFiles(List<String> remoteFilePaths, String localDirPath, ProgressCallback callback) throws SftpException {
        downloadFiles(remoteFilePaths, localDirPath, callback, OverwritePolicy.OVERWRITE, new TransferControl());
    }

    public void downloadFiles(List<String> remoteFilePaths, String localDirPath, ProgressCallback callback,
                              OverwritePolicy overwritePolicy, TransferControl control) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                if (callback != null) {
                    for (String filePath : remoteFilePaths) {
                        callback.onError(filePath, e.getMessage());
                    }
                }
                throw new RuntimeException(e);
            }

        // 单个文件失败时记录下来继续传后面的，最后统一汇报失败清单
        List<String> failedFiles = new java.util.ArrayList<>();
        for (String remoteFilePath : remoteFilePaths) {
            if (control.isCancelled()) throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "传输已取消");
            try {
                String fileName = remoteFilePath.substring(remoteFilePath.lastIndexOf('/') + 1);
                String localFilePath = localDirPath + "/" + fileName;
                if (overwritePolicy == OverwritePolicy.SKIP && java.nio.file.Files.exists(java.nio.file.Path.of(localFilePath))) continue;
                long fileSize = sftpChannel.lstat(remoteFilePath).getSize();
                long localSize = java.nio.file.Files.exists(java.nio.file.Path.of(localFilePath))
                        ? new java.io.File(localFilePath).length() : 0;
                if (overwritePolicy == OverwritePolicy.RESUME && localSize == fileSize && fileSize > 0) {
                    if (callback != null) callback.onComplete(fileName);
                    continue;
                }
                int transferMode = overwritePolicy == OverwritePolicy.RESUME
                        && localSize > 0 && localSize < fileSize ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE;
                long initial = transferMode == ChannelSftp.RESUME ? localSize : 0;

                if (callback != null) {
                    sftpChannel.get(remoteFilePath, localFilePath, new com.jcraft.jsch.SftpProgressMonitor() {
                        private long total = fileSize;
                        private long current = initial;

                        @Override
                        public void init(int op, String src, String dest, long max) {
                            // 初始化操作
                        }

                        @Override
                        public boolean count(long count) {
                            current += count;
                            if (callback != null) {
                                callback.onProgress(fileName, current, total);
                            }
                            return !control.isCancelled();
                        }

                        @Override
                        public void end() {
                            if (callback != null) {
                                callback.onComplete(fileName);
                            }
                        }
                    }, transferMode);
                } else {
                    sftpChannel.get(remoteFilePath, localFilePath, null, transferMode);
                }
            } catch (SftpException e) {
                if (callback != null) {
                    callback.onError(remoteFilePath, e.getMessage());
                }
                // 单个文件失败不中断整批传输：记录后继续，最后统一汇报
                failedFiles.add(remoteFilePath + "（" + e.getMessage() + "）");
            }
            }
            if (!failedFiles.isEmpty()) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE,
                        "以下文件下载失败：\n" + String.join("\n", failedFiles));
            }
        }
    }

    /**
     * 上传文件
     *
     * @param localFilePath  本地文件路径
     * @param remoteFilePath 远程文件路径
     */
    public void uploadFile(String localFilePath, String remoteFilePath) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
            sftpChannel.put(localFilePath, remoteFilePath);
        }
    }

    /**
     * 下载文件
     *
     * @param remoteFilePath 远程文件路径
     * @param localFilePath  本地文件路径
     */
    public void downloadFile(String remoteFilePath, String localFilePath) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
            sftpChannel.get(remoteFilePath, localFilePath);
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 0) return "Unknown";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 格式化权限
     */
    private String formatPermissions(ChannelSftp.LsEntry entry) {
        StringBuilder sb = new StringBuilder();
        SftpATTRS attr = entry.getAttrs();
        if (attr.isDir()) sb.append('d');
        else if (attr.isLink()) sb.append('l'); // 处理符号链接
        else sb.append('-');

        // 定义权限位常量，更清晰地表示权限
        int ownerRead = 0400;
        int ownerWrite = 0200;
        int ownerExecute = 0100;
        int groupRead = 0040;
        int groupWrite = 0020;
        int groupExecute = 0010;
        int othersRead = 0004;
        int othersWrite = 0002;
        int othersExecute = 0001;

        int permissions = attr.getPermissions();
        sb.append((permissions & ownerRead) != 0 ? 'r' : '-');
        sb.append((permissions & ownerWrite) != 0 ? 'w' : '-');
        sb.append((permissions & ownerExecute) != 0 ? 'x' : '-');
        sb.append((permissions & groupRead) != 0 ? 'r' : '-');
        sb.append((permissions & groupWrite) != 0 ? 'w' : '-');
        sb.append((permissions & groupExecute) != 0 ? 'x' : '-');
        sb.append((permissions & othersRead) != 0 ? 'r' : '-');
        sb.append((permissions & othersWrite) != 0 ? 'w' : '-');
        sb.append((permissions & othersExecute) != 0 ? 'x' : '-');
        return sb.toString();
    }

    /**
     * 格式化时间
     */
    private String formatTime(int time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date((long) time * 1000));
    }
    
    public Session getSession() {
        return session;
    }
    
    /**
     * 获取当前连接状态
     * 
     * @return 连接状态
     */
    public boolean isConnected() {
        synchronized (channelLock) {
            return sftpChannel != null && sftpChannel.isConnected();
        }
    }

    /**
     * 获取当前工作目录
     * 
     * @return 当前工作目录
     */
    public String getWorkingDirectory() throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
                return sftpChannel.pwd();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 切换工作目录
     * 
     * @param directory 目标目录
     */
    public void changeDirectory(String directory) throws SftpException {
        synchronized (channelLock) {
            try {
                connect();
                sftpChannel.cd(directory);
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
