/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;

/**
 * 编辑器写操作审计（方案 §5.6）：记录 sessionId / remotePath / bytes / sha256 / result，
 * 不记录文件内容。
 *
 * <p>复用现有日志设施：编辑器写入属于用户主动操作，info 级别即可追溯；失败时由调用方单独 warn。
 */
@Slf4j
public final class EditorAudit {

    private EditorAudit() {
    }

    /** 记录一次写操作（保存或外部编辑器回传）。 */
    public static void recordWrite(String sessionId, String path, byte[] content, String result) {
        try {
            String sha256 = content == null ? "-" : sha256(content);
            long bytes = content == null ? 0 : content.length;
            log.info("editor write: session={} path={} bytes={} sha256={} result={}",
                    sessionId == null ? "unknown" : sessionId,
                    path,
                    bytes,
                    sha256,
                    result == null ? "ok" : result);
        } catch (Exception failure) {
            log.warn("Unable to record editor write audit: {}", failure.getMessage());
        }
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception failure) {
            return "unavailable";
        }
    }
}
