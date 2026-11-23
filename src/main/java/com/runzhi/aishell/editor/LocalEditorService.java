/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import com.runzhi.aishell.ssh.FileTransferService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 本地文件编辑实现（方案 §11 第 5 条建议 P1 纳入：成本低，与远端共用同一文档模型）。
 *
 * <p>保存采用「写临时文件 + ATOMIC_MOVE」，避免异常中断造成半写文件；冲突检测与远端一致：
 * 打开时记录 mtime（秒）与 size，保存前比对，不一致抛 {@link FileTransferService.RemoteFileChangedException}。
 */
@Slf4j
public final class LocalEditorService implements EditorService {

    public static final String SESSION_ID = "local";

    private static final String SESSION_TAG = SESSION_ID;

    @Override
    public String sessionId() {
        return SESSION_TAG;
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
        Path file = Path.of(path);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (AccessDeniedException e) {
            throw new EditorOpenException(EditorService.OpenFailure.PERMISSION_DENIED, "Permission denied: " + path, e);
        } catch (IOException e) {
            throw new EditorOpenException(EditorService.OpenFailure.NOT_FOUND, "File not found: " + path, e);
        } catch (Exception e) {
            throw new EditorOpenException(EditorService.OpenFailure.OTHER, "stat failed: " + e.getMessage(), e);
        }
        if (attrs.isDirectory()) {
            throw new EditorOpenException(EditorService.OpenFailure.DIRECTORY, "Is a directory: " + path);
        }
        int maxBytes = readOnly ? EditorPreferences.maxExternalBytes() : EditorPreferences.maxInlineBytes();
        if (attrs.size() > maxBytes) {
            throw new EditorOpenException(EditorService.OpenFailure.TOO_LARGE,
                    "File exceeds the read limit", attrs.size());
        }

        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (AccessDeniedException e) {
            throw new EditorOpenException(EditorService.OpenFailure.PERMISSION_DENIED, "Permission denied: " + path, e);
        } catch (IOException e) {
            throw new EditorOpenException(EditorService.OpenFailure.OTHER, "read failed: " + e.getMessage(), e);
        }
        if (data.length > maxBytes) {
            throw new EditorOpenException(EditorService.OpenFailure.TOO_LARGE,
                    "File exceeds the read limit", data.length);
        }

        TextCodec.Detection detection = TextCodec.detect(data, EditorPreferences.fallbackEncoding());
        String name = file.getFileName() == null ? path : file.getFileName().toString();
        EditorDocument doc = new EditorDocument(SESSION_TAG, null, file.toAbsolutePath(), name,
                TextCodec.languageOf(name));
        applyDetection(doc, detection, attrs, data, file);
        if (readOnly) doc.setReadOnly(true);
        return doc;
    }

    private static void applyDetection(EditorDocument doc, TextCodec.Detection detection, BasicFileAttributes attrs,
                                        byte[] data, Path file) {
        doc.setEncoding(detection.encoding());
        doc.setBom(detection.bom());
        doc.setEol(detection.eol());
        doc.setBinary(detection.binary());
        doc.setPreviewFallback(detection.previewFallback());
        doc.setLoadedMtime(attrs.lastModifiedTime().toMillis() / 1000);
        doc.setLoadedSize(attrs.size());
        doc.setLoadedBytes(data);
        doc.setSymlink(attrs.isSymbolicLink());
        doc.setReadOnly(!file.toFile().canWrite() || detection.binary() || detection.previewFallback());
    }

    @Override
    public void save(EditorDocument doc, byte[] content, boolean force) throws Exception {
        if (doc == null || doc.isRemote()) throw new IllegalArgumentException("Not a local document");
        if (doc.readOnly()) throw new IllegalStateException("Document is read-only: " + doc.displayPath());
        Path file = doc.localPath();
        if (force || doc.loadedMtime() <= 0) {
            writeAtomically(file, content);
        } else {
            try {
                BasicFileAttributes current = Files.readAttributes(file, BasicFileAttributes.class);
                if (current.lastModifiedTime().toMillis() / 1000 != doc.loadedMtime()
                        || current.size() != doc.loadedSize()) {
                    throw new FileTransferService.RemoteFileChangedException(
                            doc.loadedMtime(), current.lastModifiedTime().toMillis() / 1000);
                }
            } catch (FileTransferService.RemoteFileChangedException already) {
                throw already;
            } catch (IOException ignored) {
                // stat 失败（如文件被删除）交给写入阶段暴露具体错误
            }
            writeAtomically(file, content);
        }
        try {
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
            doc.setLoadedMtime(after.lastModifiedTime().toMillis() / 1000);
            doc.setLoadedSize(after.size());
        } catch (IOException ignored) {
            // 保存已成功；基线更新失败只影响下次冲突检测精度
        }
        EditorAudit.recordWrite(SESSION_TAG, file.toString(), content, "saved");
    }

    @Override
    public EditorDocument reload(EditorDocument doc) throws EditorOpenException {
        if (doc == null || doc.isRemote()) throw new IllegalArgumentException("Not a local document");
        return open(doc.localPath().toString());
    }

    private static void writeAtomically(Path file, byte[] content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) parent = Path.of(".");
        Path temp = Files.createTempFile(parent, ".aishell-edit-", ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw e;
        }
    }
}
