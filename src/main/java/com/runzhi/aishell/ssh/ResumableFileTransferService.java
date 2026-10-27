/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.ssh;

import com.jcraft.jsch.*;

import java.io.*;
import java.util.Arrays;

/**
 * 支持断点续传的文件传输服务，继承自 FileTransferService。
 * <p>
 * 续传的完成判定不只看大小：同名同大小的文件完全可能是别的内容，
 * 因此大小相等时还会比对首尾块（各 {@link #VERIFY_CHUNK} 字节），不一致就整体重传；
 * 远程（或本地）残留文件比目标更大时同样整体重传，而不是在旧文件后追加。
 * 传输过程中响应取消：{@link TransferControl} 或线程中断都可以中止传输。
 */
public class ResumableFileTransferService extends FileTransferService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResumableFileTransferService.class);

    /** 完成判定时比对的块大小：取首尾各 4 KiB，避免对整个大文件计算哈希。 */
    static final int VERIFY_CHUNK = 4096;

    public ResumableFileTransferService(Session session) {
        super(session);
    }

    public ResumableFileTransferService(String host, int port, String username, String password, String privateKeyPath) {
        super(host, port, username, password, privateKeyPath);
    }

    /**
     * 支持断点续传的上传
     *
     * @param localFilePath  本地文件路径
     * @param remoteFilePath 远程文件路径
     * @param callback       进度回调
     */
    public void resumableUpload(String localFilePath, String remoteFilePath, ProgressCallback callback) throws SftpException {
        resumableUpload(localFilePath, remoteFilePath, callback, null);
    }

    /**
     * 支持断点续传的上传，可通过 {@link TransferControl} 取消。
     */
    public void resumableUpload(String localFilePath, String remoteFilePath, ProgressCallback callback,
                                TransferControl control) throws SftpException {
        try {
            connect();
        } catch (JSchException e) {
            if (callback != null) {
                callback.onError(localFilePath, e.getMessage());
            }
            throw new RuntimeException(e);
        }

        File localFile = new File(localFilePath);
        if (!localFile.exists()) {
            throw new SftpException(4, "Local file does not exist: " + localFilePath);
        }

        long localFileSize = localFile.length();
        long remoteFileSize = 0;

        // 检查远程文件是否存在及大小
        try {
            remoteFileSize = sftpChannel.lstat(remoteFilePath).getSize();
        } catch (SftpException e) {
            // 远程文件不存在，从头开始上传
            remoteFileSize = 0;
        }

        if (localFileSize > 0 && remoteFileSize == localFileSize) {
            // 大小相等未必是同一份文件：比对首尾块，一致才认可已完成。
            if (remoteChunksMatch(localFilePath, remoteFilePath, localFileSize)) {
                if (callback != null) {
                    callback.onComplete(localFile.getName());
                }
                return;
            }
            // 同名同大小但内容不同：旧逻辑会误判为「已完成」，这里必须整体重传。
        }

        // 只有「远程文件比本地小且非空」时才能续传；远程更大或内容不符时整体覆盖重传。
        boolean resume = remoteFileSize > 0 && remoteFileSize < localFileSize;
        int transferMode = resume ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE;
        if (resume && callback != null) {
            callback.onProgress(localFile.getName(), remoteFileSize, localFileSize);
        }

        ResumableSftpProgressMonitor progressMonitor = new ResumableSftpProgressMonitor(
                callback, localFile.getName(), localFileSize, control);
        try {
            sftpChannel.put(localFilePath, remoteFilePath, progressMonitor, transferMode);
        } catch (SftpException e) {
            if (callback != null) {
                callback.onError(localFile.getName(), e.getMessage());
            }
            throw new SftpException(4, "Error during file transfer: " + e.getMessage());
        }
        if (progressMonitor.isCancelled()) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "传输已取消");
        }

        if (callback != null) {
            callback.onComplete(localFile.getName());
        }
    }

    /**
     * 支持断点续传的下载
     *
     * @param remoteFilePath 远程文件路径
     * @param localFilePath  本地文件路径
     * @param callback       进度回调
     */
    public void resumableDownload(String remoteFilePath, String localFilePath, ProgressCallback callback) throws SftpException {
        resumableDownload(remoteFilePath, localFilePath, callback, null);
    }

    /**
     * 支持断点续传的下载，可通过 {@link TransferControl} 取消。
     */
    public void resumableDownload(String remoteFilePath, String localFilePath, ProgressCallback callback,
                                  TransferControl control) throws SftpException {
        try {
            connect();
        } catch (JSchException e) {
            if (callback != null) {
                callback.onError(remoteFilePath, e.getMessage());
            }
            throw new RuntimeException(e);
        }

        // 检查远程文件是否存在及大小
        long remoteFileSize;
        try {
            remoteFileSize = sftpChannel.lstat(remoteFilePath).getSize();
        } catch (SftpException e) {
            throw new SftpException(4, "Remote file does not exist: " + remoteFilePath);
        }

        String fileName = remoteFilePath.substring(remoteFilePath.lastIndexOf('/') + 1);
        File localFile = new File(localFilePath);

        // 检查本地文件是否存在及大小
        long localFileSize = localFile.exists() ? localFile.length() : 0;

        if (remoteFileSize > 0 && localFileSize == remoteFileSize) {
            if (remoteChunksMatch(localFilePath, remoteFilePath, remoteFileSize)) {
                if (callback != null) {
                    callback.onComplete(fileName);
                }
                return;
            }
        }

        // 只有「本地文件比远程小且非空」时才能续传；本地更大或内容不符时整体覆盖重传。
        boolean resume = localFileSize > 0 && localFileSize < remoteFileSize;
        int transferMode = resume ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE;
        if (resume && callback != null) {
            callback.onProgress(fileName, localFileSize, remoteFileSize);
        }

        ResumableSftpProgressMonitor progressMonitor = new ResumableSftpProgressMonitor(
                callback, fileName, remoteFileSize, control);
        try {
            sftpChannel.get(remoteFilePath, localFilePath, progressMonitor, transferMode);
        } catch (SftpException e) {
            if (callback != null) {
                callback.onError(fileName, e.getMessage());
            }
            throw new SftpException(4, "Error during file transfer: " + e.getMessage());
        }
        if (progressMonitor.isCancelled()) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "传输已取消");
        }

        if (callback != null) {
            callback.onComplete(fileName);
        }
    }

    /**
     * 大小相等时比对首尾块，判断两端文件是否真的是同一份内容。
     * <p>
     * 只取首尾各 {@link #VERIFY_CHUNK} 字节，避免对整个大文件计算哈希；
     * 任何读取异常都当作「不匹配」处理，触发重传而不是误判为已完成。
     */
    boolean remoteChunksMatch(String localFilePath, String remoteFilePath, long size) {
        try {
            int chunk = (int) Math.min(VERIFY_CHUNK, size);
            byte[] localHead = readLocalChunk(localFilePath, 0, chunk);
            byte[] remoteHead = readRemoteChunk(remoteFilePath, 0, chunk);
            if (!Arrays.equals(localHead, remoteHead)) return false;
            long tailOffset = size - chunk;
            if (tailOffset > 0) {
                byte[] localTail = readLocalChunk(localFilePath, tailOffset, chunk);
                byte[] remoteTail = readRemoteChunk(remoteFilePath, tailOffset, chunk);
                if (!Arrays.equals(localTail, remoteTail)) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Content verification failed for {}: {}", remoteFilePath, e.getMessage());
            return false;
        }
    }

    byte[] readLocalChunk(String path, long offset, int len) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            file.seek(offset);
            byte[] buffer = new byte[len];
            int read = 0;
            while (read < len) {
                int n = file.read(buffer, read, len - read);
                if (n < 0) break;
                read += n;
            }
            if (read != len) {
                throw new IOException("Local file is shorter than expected: " + path + " @" + offset);
            }
            return buffer;
        }
    }

    byte[] readRemoteChunk(String remoteFilePath, long offset, int len) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(len);
        // RESUME 模式下 skip 由服务端寻址（SFTP READ 请求带 offset），
        // count() 返回 false 时 JSch 会干净地停止传输，不会抛异常。
        sftpChannel.get(remoteFilePath, output, new SftpProgressMonitor() {
            private long transferred = 0;

            @Override
            public void init(int op, String src, String dest, long max) {
                // RESUME 模式下 JSch 会先以 skip 调用一次 count，transferred 从 offset 起算
            }

            @Override
            public boolean count(long count) {
                transferred += count;
                // 读完这一块即停，避免把整个大文件都拉下来
                return transferred < offset + len;
            }

            @Override
            public void end() {
                // 传输结束
            }
        }, ChannelSftp.RESUME, offset);
        byte[] data = output.toByteArray();
        if (data.length < len) {
            throw new IOException("Remote file is shorter than expected: " + remoteFilePath + " @" + offset);
        }
        return Arrays.copyOf(data, len);
    }

    /**
     * 自定义SFTP进度监控器，支持断点续传与取消。
     * <p>
     * 取消方式有两种：外部 {@link TransferControl#cancel()}，或中断执行线程。
     * count() 返回 false 后 JSch 会停止传输，调用方再根据 {@link #isCancelled()}
     * 抛出「传输已取消」异常。
     */
    private static class ResumableSftpProgressMonitor implements SftpProgressMonitor {
        private final ProgressCallback callback;
        private final String fileName;
        private final long totalSize;
        private final TransferControl control;
        private long currentBytes;
        private volatile boolean cancelled;

        public ResumableSftpProgressMonitor(ProgressCallback callback, String fileName, long totalSize,
                                            TransferControl control) {
            this.callback = callback;
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.control = control;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            // 续传模式下 JSch 会以已传字节数调用一次 count()，currentBytes 自动从断点起算
        }

        @Override
        public boolean count(long count) {
            currentBytes += count;
            if (callback != null) {
                callback.onProgress(fileName, currentBytes, totalSize);
            }
            if (control != null && control.isCancelled()) {
                cancelled = true;
                return false;
            }
            if (Thread.currentThread().isInterrupted()) {
                cancelled = true;
                return false;
            }
            return true;
        }

        @Override
        public void end() {
            // 传输结束
        }

        boolean isCancelled() {
            return cancelled;
        }
    }
}
