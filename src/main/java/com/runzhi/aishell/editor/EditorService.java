/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

/**
 * 编辑器文本服务：打开 / 保存 / 重新加载，远端与本地各一套实现（方案 §4.1）。
 *
 * <p>所有方法都设计为在后台线程调用：实现内部直接做阻塞 I/O，不在 FX 线程执行。
 */
public interface EditorService {

    /** 打开失败原因：UI 据此给出保护提示而非 crashing。 */
    enum OpenFailure { NOT_FOUND, DIRECTORY, TOO_LARGE, PERMISSION_DENIED, CONNECT_ERROR, OTHER }

    /** 会话身份：远端为 SSH tabId，本地为 "local"。 */
    String sessionId();

    /**
     * 打开文件：stat → 超限/目录校验 → 读取 → 编码检测 → 文档（含只读/二进制状态）。
     *
     * @throws EditorOpenException 超限（携带 stat 大小）、目录、无权限、连接异常等
     */
    EditorDocument open(String path) throws EditorOpenException;

    /**
     * 只读打开（绕过内置编辑器上限，但受外部编辑器上限约束）：大文件保护流程的
     * 「仍以只读打开」分支，文档 readOnly 恒为 true。
     */
    EditorDocument openReadOnly(String path) throws EditorOpenException;

    /**
     * 保存：mtime/size 冲突校验 + 写入 + 审计；成功后更新文档基线（mtime/size）。
     *
     * @param force true 表示用户在冲突对话框中选择了「覆盖」，跳过冲突校验
     * @throws com.runzhi.aishell.ssh.FileTransferService.RemoteFileChangedException 远端已变化
     */
    void save(EditorDocument doc, byte[] content, boolean force) throws Exception;

    /** 重新加载：放弃本地修改，按当前远端内容重建文档。 */
    EditorDocument reload(EditorDocument doc) throws EditorOpenException;

    /** 打开异常：携带原因与（TOO_LARGE 时）文件实际大小，供 UI 弹出保护提示。 */
    class EditorOpenException extends Exception {
        private final OpenFailure reason;
        private final long size;

        public EditorOpenException(OpenFailure reason, String message) {
            this(reason, message, -1);
        }

        public EditorOpenException(OpenFailure reason, String message, long size) {
            super(message);
            this.reason = reason;
            this.size = size;
        }

        public EditorOpenException(OpenFailure reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
            this.size = -1;
        }

        public OpenFailure reason() { return reason; }
        public long size() { return size; }
    }
}
