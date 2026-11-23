/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import java.nio.file.Path;

/**
 * 一份打开的文档：远端文件 / 本地文件共用的字节级模型（方案 §4.1）。
 *
 * <p> tabs 的 UI 状态由 EditorTab 持有；本类只描述「内容 + 来源 + 冲突基线」。
 */
public final class EditorDocument {

    /** 会话身份：远端文档为 SSH tabId，本地文档为固定前缀 "local"。 */
    private final String sessionId;
    /** 远端绝对路径；本地文档为 null。 */
    private final String remotePath;
    /** 本地文件路径；远端文档为 null。 */
    private final Path localPath;

    private final String displayName;
    private final String language;

    private volatile String encoding;
    private volatile boolean bom;
    private volatile TextCodec.Eol eol;
    private volatile boolean binary;
    private volatile boolean previewFallback;

    /** 打开时记录的远端 mtime（秒）与字节数；保存前以此为基线做冲突检测。 */
    private volatile long loadedMtime;
    private volatile long loadedSize;

    /** 打开时的原始字节：切换编码重载与外部编辑器回传比对复用，避免重新下载。 */
    private volatile byte[] loadedBytes;

    private volatile boolean dirty;
    private volatile boolean readOnly;
    /** 来源 stat 标记：目录 / 符号链接。 */
    private volatile boolean symlink;

    public EditorDocument(String sessionId, String remotePath, Path localPath,
                          String displayName, String language) {
        this.sessionId = sessionId;
        this.remotePath = remotePath;
        this.localPath = localPath;
        this.displayName = displayName;
        this.language = language;
    }

    public String sessionId() { return sessionId; }
    public String remotePath() { return remotePath; }
    public Path localPath() { return localPath; }

    /** 状态栏与标签标题用的文件名。 */
    public String displayName() { return displayName; }
    public String language() { return language; }

    /** 窗口复用键：同一会话 + 同一路径只开一个标签。 */
    public String tabKey() {
        return (sessionId == null ? "none" : sessionId) + "|" + (remotePath != null ? remotePath : localPath);
    }

    public String encoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
    public boolean bom() { return bom; }
    public void setBom(boolean bom) { this.bom = bom; }
    public TextCodec.Eol eol() { return eol; }
    public void setEol(TextCodec.Eol eol) { this.eol = eol; }
    public boolean binary() { return binary; }
    public void setBinary(boolean binary) { this.binary = binary; }
    public boolean previewFallback() { return previewFallback; }
    public void setPreviewFallback(boolean previewFallback) { this.previewFallback = previewFallback; }

    public long loadedMtime() { return loadedMtime; }
    public void setLoadedMtime(long loadedMtime) { this.loadedMtime = loadedMtime; }
    public long loadedSize() { return loadedSize; }
    public void setLoadedSize(long loadedSize) { this.loadedSize = loadedSize; }
    public byte[] loadedBytes() { return loadedBytes; }
    public void setLoadedBytes(byte[] loadedBytes) { this.loadedBytes = loadedBytes; }

    public boolean dirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }
    public boolean readOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean symlink() { return symlink; }
    public void setSymlink(boolean symlink) { this.symlink = symlink; }

    public boolean isRemote() { return remotePath != null; }

    /** 状态栏显示的完整路径。 */
    public String displayPath() {
        return remotePath != null ? remotePath : (localPath != null ? localPath.toString() : displayName);
    }
}
