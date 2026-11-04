/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.nio.file.Path;
import java.util.List;

/**
 * ZMODEM 与界面的交互接口：终端连接器在检测到传输后回调这些方法。
 * 回调可能运行在终端读取线程上，实现方需自行切回 UI 线程。
 */
public interface ZModemSupport {

    /** 接收文件时的保存目录。 */
    Path downloadDirectory();

    /**
     * 发送文件时选择本地文件。
     * 返回空列表表示用户取消；该方法阻塞直到用户做出选择。
     */
    List<Path> chooseFilesToSend();

    /** 一个文件开始传输。direction 为 "上传" 或 "下载"。 */
    void onFileStart(String direction, String fileName, long size);

    /** 已传输字节数。 */
    void onProgress(long transferred, long total);

    /** 单个文件完成。 */
    void onFileDone(String fileName, boolean success);

    /** 整个会话结束（不论成功、失败还是取消）。 */
    void onSessionFinished(boolean success, String message);

    /** 是否已被用户取消（界面取消按钮）。 */
    boolean isCancelled();

    /** 界面侧的当前取消状态复位。 */
    void resetCancel();
}
