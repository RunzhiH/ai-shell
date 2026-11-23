/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import com.runzhi.aishell.ssh.FileTransferService;
import com.runzhi.aishell.ssh.SSHService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * 基于 SSHService 的远端编辑实现（方案 §4.1）：所有 SFTP 操作在调用线程（后台）执行，
 * 阻塞由上层用 ExecutorService 隔离。
 */
@Slf4j
public final class RemoteEditorService implements EditorService {

    private final SSHService sshService;
    private final String tabId;

    public RemoteEditorService(SSHService sshService, String tabId) {
        this.sshService = sshService;
        this.tabId = tabId;
    }

    @Override
    public String sessionId() {
        return tabId;
    }

    @Override
    public EditorDocument open(String path) throws EditorOpenException {
        return open(path, false);
    }

    @Override
    public EditorDocument openReadOnly(String path) throws EditorOpenException {
        return open(path, true);
    }

    private EditorDocument open(String path, boolean readOnly) throws EditorOpenException {
        FileTransferService.RemoteFileStat stat;
        try {
            stat = sshService.statForTab(tabId, path);
        } catch (SftpException e) {
            throw openFailure(e, path);
        } catch (IllegalStateException e) {
            throw new EditorService.EditorOpenException(EditorService.OpenFailure.CONNECT_ERROR,
                    "Session not connected: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new EditorService.EditorOpenException(EditorService.OpenFailure.OTHER,
                    "stat failed: " + e.getMessage(), e);
        }
        if (stat.directory()) {
            throw new EditorService.EditorOpenException(EditorService.OpenFailure.DIRECTORY, "Is a directory: " + path);
        }
        int maxBytes = readOnly ? EditorPreferences.maxExternalBytes() : EditorPreferences.maxInlineBytes();
        if (stat.size() > maxBytes) {
            throw new EditorService.EditorOpenException(EditorService.OpenFailure.TOO_LARGE,
                    "File exceeds the read limit", stat.size());
        }

        byte[] data;
        try {
            data = sshService.readBytesForTab(tabId, path, maxBytes);
        } catch (SftpException e) {
            throw openFailure(e, path);
        } catch (IllegalArgumentException e) {
            // 读取期间文件变大超过上限
            throw new EditorService.EditorOpenException(EditorService.OpenFailure.TOO_LARGE,
                    e.getMessage(), stat.size());
        } catch (Exception e) {
            throw new EditorService.EditorOpenException(EditorService.OpenFailure.OTHER,
                    "read failed: " + e.getMessage(), e);
        }

        TextCodec.Detection detection = TextCodec.detect(data, EditorPreferences.fallbackEncoding());
        EditorDocument doc = new EditorDocument(tabId, path, null, fileNameOf(path),
                TextCodec.languageOf(fileNameOf(path)));
        applyDetection(doc, detection, stat, data);
        if (readOnly) doc.setReadOnly(true);
        return doc;
    }

    private static void applyDetection(EditorDocument doc, TextCodec.Detection detection,
                                        FileTransferService.RemoteFileStat stat, byte[] data) {
        doc.setEncoding(detection.encoding());
        doc.setBom(detection.bom());
        doc.setEol(detection.eol());
        doc.setBinary(detection.binary());
        doc.setPreviewFallback(detection.previewFallback());
        doc.setLoadedMtime(stat.mtimeSeconds());
        doc.setLoadedSize(stat.size());
        doc.setLoadedBytes(data);
        doc.setSymlink(stat.symlink());
        // 只读：权限不足 / 二进制 / 编码回退预览
        doc.setReadOnly(!stat.writable() || detection.binary() || detection.previewFallback());
    }

    @Override
    public void save(EditorDocument doc, byte[] content, boolean force) throws Exception {
        if (doc == null || !doc.isRemote()) throw new IllegalArgumentException("Not a remote document");
        if (doc.readOnly()) throw new IllegalStateException("Document is read-only: " + doc.displayPath());
        long expectedMtime = force ? 0 : doc.loadedMtime();
        // size 辅助校验：只对已存在文件做（loadedSize>=0 且打开时大小已知）
        if (!force && doc.loadedSize() >= 0 && expectedMtime > 0) {
            try {
                FileTransferService.RemoteFileStat current = sshService.statForTab(tabId, doc.remotePath());
                if (current.size() != doc.loadedSize()) {
                    throw new FileTransferService.RemoteFileChangedException(doc.loadedMtime(), current.mtimeSeconds());
                }
            } catch (FileTransferService.RemoteFileChangedException already) {
                throw already;
            } catch (Exception ignored) {
                // stat 失败交给 writeBytes 内部校验，不在此阻断
            }
        }
        sshService.writeBytesForTab(tabId, doc.remotePath(), content, expectedMtime);
        // 成功：更新基线，清除 dirty 由控制器处理
        try {
            FileTransferService.RemoteFileStat after = sshService.statForTab(tabId, doc.remotePath());
            doc.setLoadedMtime(after.mtimeSeconds());
            doc.setLoadedSize(after.size());
        } catch (Exception ignored) {
            // 保存已成功；基线更新失败只影响下次冲突检测精度
        }
        EditorAudit.recordWrite(tabId, doc.remotePath(), content, "saved");
    }

    @Override
    public EditorDocument reload(EditorDocument doc) throws EditorOpenException {
        if (doc == null || !doc.isRemote()) throw new IllegalArgumentException("Not a remote document");
        return open(doc.remotePath());
    }

    private static EditorService.EditorOpenException openFailure(SftpException e, String path) {
        return switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE -> new EditorService.EditorOpenException(
                    EditorService.OpenFailure.NOT_FOUND, "File not found: " + path, e);
            case ChannelSftp.SSH_FX_PERMISSION_DENIED -> new EditorService.EditorOpenException(
                    EditorService.OpenFailure.PERMISSION_DENIED, "Permission denied: " + path, e);
            default -> new EditorService.EditorOpenException(
                    EditorService.OpenFailure.OTHER, "SFTP error: " + e.getMessage(), e);
        };
    }

    private static String fileNameOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
