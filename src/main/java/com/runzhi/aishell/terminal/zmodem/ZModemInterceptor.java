/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 在终端字节流中识别 ZMODEM 会话并接管传输。
 * <p>
 * 终端输出逐字节经过 {@link #onRawByte(int)}；一旦出现 ZMODEM 帧前导
 * （{@code ** ZDLE B} 等），已消费的字节退回 {@link ZModemStream} 并同步执行
 * 一次完整会话（对端运行 sz 则接收，运行 rz 则发送）。会话期间调用方（终端
 * 读取线程）阻塞，界面由 {@link ZModemSupport} 驱动。
 */
public final class ZModemInterceptor {

    private static final byte[] MAGIC_PREFIX = {0x2A, 0x2A, 0x18};   // ** ZDLE
    private static final int WINDOW = 4;

    private final ZModemStream stream;
    private final OutputStream output;
    private final ZModemSupport support;
    private final boolean enabled;

    private final int[] window = new int[WINDOW];
    private int windowLength;
    private volatile ZModemSession activeSession;
    private volatile boolean transferring;

    public ZModemInterceptor(ZModemStream stream, OutputStream output, ZModemSupport support) {
        this.stream = stream;
        this.output = output;
        this.support = support;
        this.enabled = support != null && !Boolean.getBoolean("aishell.zmodem.disabled");
    }

    /** 传输进行中（此期间的终端键盘输入应丢弃）。 */
    public boolean isTransferring() {
        return transferring;
    }

    /** 用户请求取消当前传输。 */
    public void cancel() {
        ZModemSession session = activeSession;
        if (session != null) {
            session.cancel();
            try {
                ZModemCodec.writeCancel(output);
            } catch (IOException ignored) {
                // 对端可能已断开，取消本身已经生效
            }
        }
    }

    /**
     * 处理一个刚从终端流中读出的原始字节。
     * 返回 true 表示该字节（及其之前的若干字节）已被 ZMODEM 会话消费。
     */
    public boolean onRawByte(int b) throws IOException {
        if (!enabled) {
            return false;
        }
        window[windowLength++] = b;
        if (windowLength < WINDOW) {
            return false;
        }
        if (isMagic()) {
            byte[] magic = new byte[WINDOW];
            for (int i = 0; i < WINDOW; i++) {
                magic[i] = (byte) window[i];
            }
            windowLength = 0;
            stream.unread(magic);
            runSession();
            return true;
        }
        System.arraycopy(window, 1, window, 0, WINDOW - 1);
        windowLength = WINDOW - 1;
        return false;
    }

    private boolean isMagic() {
        if (window[0] != MAGIC_PREFIX[0] || window[1] != MAGIC_PREFIX[1] || window[2] != MAGIC_PREFIX[2]) {
            return false;
        }
        // 帧指示：十六进制头 'B' 是控制帧的常规形式；'A'/'C' 为二进制头
        return window[3] == 'B' || window[3] == 'A' || window[3] == 'C';
    }

    private void runSession() throws IOException {
        transferring = true;
        support.resetCancel();
        ZModemSession session = new ZModemSession();
        activeSession = session;
        String message = null;
        boolean success = false;
        try {
            ZModemHeader first = probeFirstHeader();
            if (first == null) {
                return;
            }
            if (first.type() == ZModem.ZRINIT) {
                success = runSend(session, first);
            } else if (first.type() == ZModem.ZRQINIT || first.type() == ZModem.ZSINIT
                    || first.type() == ZModem.ZFILE) {
                success = runReceive(session);
            } else {
                // 不是可识别的会话起点：放回字节，交给终端显示
                message = "未识别的 ZMODEM 帧";
                cancelQuietly(session);
            }
        } catch (ZModemCancelledException e) {
            message = "传输已取消";
        } catch (IOException e) {
            message = "传输失败：" + e.getMessage();
        } finally {
            activeSession = null;
            transferring = false;
            if (!success && message == null) {
                message = "传输未完成";
            }
            support.onSessionFinished(success, message);
        }
    }

    /** 读取首帧类型后把字节全部退回，供会话正式流程重新解析。 */
    private ZModemHeader probeFirstHeader() throws IOException {
        ByteArrayOutputStream recorded = new ByteArrayOutputStream();
        InputStream replay = new InputStream() {
            @Override
            public int read() throws IOException {
                int b = stream.read();
                if (b >= 0) {
                    recorded.write(b);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = stream.read(buffer, offset, length);
                if (n > 0) {
                    recorded.write(buffer, offset, n);
                }
                return n;
            }
        };
        ZModemHeader header = ZModemCodec.readHeader(replay);
        stream.unread(recorded.toByteArray());
        return header;
    }

    private boolean runSend(ZModemSession session, ZModemHeader first) throws IOException {
        List<Path> selected = support.chooseFilesToSend();
        if (selected == null || selected.isEmpty()) {
            cancelQuietly(session);
            return false;
        }
        List<ZModemOutFile> files = new ArrayList<>();
        for (Path p : selected) {
            if (Files.isRegularFile(p)) {
                files.add(ZModemOutFile.of(p));
            }
        }
        if (files.isEmpty()) {
            cancelQuietly(session);
            return false;
        }
        session.send(stream, output, files, new ProgressBridge("上传"), first.flagsLow());
        return true;
    }

    private boolean runReceive(ZModemSession session) throws IOException {
        Path dir = support.downloadDirectory();
        Files.createDirectories(dir);
        session.receive(stream, output, dir, new ProgressBridge("下载"));
        return true;
    }

    private void cancelQuietly(ZModemSession session) {
        try {
            ZModemCodec.writeCancel(output);
        } catch (IOException ignored) {
            // 对端可能已断开
        }
        session.cancel();
    }

    /** 把协议回调转成界面回调。 */
    private final class ProgressBridge implements ZModemProgressListener {
        private final String direction;
        private long total = -1;
        private long transferred;

        ProgressBridge(String direction) {
            this.direction = direction;
        }

        @Override
        public void onFileStart(String name, long size) {
            total = size;
            transferred = 0;
            support.onFileStart(direction, name, size);
        }

        @Override
        public void onProgress(long bytes) {
            transferred = bytes;
            support.onProgress(bytes, total);
            if (support.isCancelled()) {
                cancel();
            }
        }

        @Override
        public void onFileDone(String name, long size) {
            support.onFileDone(name, true);
        }
    }
}
