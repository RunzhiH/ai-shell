/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.io.IOException;
import java.io.InputStream;

/**
 * 原始字节流 + 回退缓冲：终端读取路径已经消费掉的字节（如检测到的 ZMODEM 前导）
 * 可以退回流中，交给协议引擎重新解析。
 */
public final class ZModemStream extends InputStream {

    private final InputStream source;
    private byte[] pushback = new byte[0];

    public ZModemStream(InputStream source) {
        this.source = source;
    }

    /** 把字节放回流的开头（按给定顺序先读出）。 */
    public void unread(byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return;
        }
        byte[] merged = new byte[length + pushback.length];
        System.arraycopy(bytes, offset, merged, 0, length);
        System.arraycopy(pushback, 0, merged, length, pushback.length);
        pushback = merged;
    }

    public void unread(byte[] bytes) {
        unread(bytes, 0, bytes.length);
    }

    @Override
    public int read() throws IOException {
        if (pushback.length > 0) {
            int b = pushback[0] & 0xff;
            byte[] rest = new byte[pushback.length - 1];
            System.arraycopy(pushback, 1, rest, 0, rest.length);
            pushback = rest;
            return b;
        }
        return source.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (pushback.length > 0) {
            int n = Math.min(length, pushback.length);
            System.arraycopy(pushback, 0, buffer, offset, n);
            byte[] rest = new byte[pushback.length - n];
            System.arraycopy(pushback, n, rest, 0, rest.length);
            pushback = rest;
            return n;
        }
        return source.read(buffer, offset, length);
    }

    @Override
    public int available() throws IOException {
        return pushback.length + source.available();
    }
}
