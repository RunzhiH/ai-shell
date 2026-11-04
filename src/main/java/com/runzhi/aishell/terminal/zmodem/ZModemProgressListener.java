/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

/**
 * ZMODEM 传输进度回调。全部方法在 ZMODEM 会话线程上调用，实现方需自行切到 UI 线程。
 */
public interface ZModemProgressListener {

    /** 开始接收/发送一个文件（size 为对方，未知时为 -1）。 */
    void onFileStart(String name, long size);

    /** 累计已传输字节数（每个数据子包回调一次）。 */
    void onProgress(long bytes);

    /** 单个文件传输完成。 */
    void onFileDone(String name, long size);
}
