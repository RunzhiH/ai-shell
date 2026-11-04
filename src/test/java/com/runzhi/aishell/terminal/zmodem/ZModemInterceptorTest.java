/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拦截器集成测试：用本项目的协议实现模拟对端 sz/rz，
 * 验证终端字节流中的检测、接管与恢复。
 */
class ZModemInterceptorTest {

    @TempDir
    Path tempDir;

    /** 对端运行 sz（发送），本地应检测并下载。 */
    @Test
    void downloadTriggeredByRemoteSz() throws Exception {
        Channel remoteToLocal = new Channel();
        Channel localToRemote = new Channel();
        ZModemStream stream = new ZModemStream(remoteToLocal.input());
        StubSupport support = new StubSupport(tempDir.resolve("downloads"));
        ZModemInterceptor interceptor = new ZModemInterceptor(stream, localToRemote.output(), support);

        Path source = tempDir.resolve("payload.bin");
        byte[] content = new byte[20_000];
        new Random(3).nextBytes(content);
        Files.write(source, content);

        Thread remote = new Thread(() -> {
            try {
                new ZModemSession().send(localToRemote.input(), remoteToLocal.output(),
                        List.of(ZModemOutFile.of(source)), new NopListener());
            } catch (Throwable t) {
                support.failures.add(t);
            }
        }, "remote-sz");
        remote.start();

        int terminalBytes = feedTerminalLoop(stream, interceptor);
        remote.join(10_000);
        remoteToLocal.close();
        localToRemote.close();

        assertTrue(support.failures.isEmpty(), () -> "remote failures: " + support.failures);
        assertFalse(remote.isAlive(), "remote sz did not finish");
        assertArrayEquals(content, Files.readAllBytes(support.downloadDir.resolve("payload.bin")));
        assertEquals(1, support.startedCount, "expected exactly one ZMODEM session");
        assertTrue(support.finishedSuccess, "session should finish successfully");
        // 前导的前 3 字节（"**" 与 ZDLE）先到达终端，第 4 字节触发接管；协议数据不泄露
        assertEquals(3, terminalBytes, "terminal should only receive the invisible preamble prefix");
    }

    /** 对端运行 rz（接收），本地应弹出选择并上传。 */
    @Test
    void uploadTriggeredByRemoteRz() throws Exception {
        Channel remoteToLocal = new Channel();
        Channel localToRemote = new Channel();
        ZModemStream stream = new ZModemStream(remoteToLocal.input());
        StubSupport support = new StubSupport(tempDir.resolve("unused"));
        ZModemInterceptor interceptor = new ZModemInterceptor(stream, localToRemote.output(), support);

        byte[] content = new byte[7_500];
        new Random(11).nextBytes(content);
        Path localFile = tempDir.resolve("upload.bin");
        Files.write(localFile, content);
        support.filesToSend = List.of(localFile);

        Path remoteDir = Files.createDirectories(tempDir.resolve("remote"));
        Thread remote = new Thread(() -> {
            try {
                // 模拟 lrzsz rz 启动时主动发送 ZRINIT
                ZModemCodec.writeHexHeader(remoteToLocal.output(), ZModem.ZRINIT,
                        ZModemHeader.flagsData(
                                ZModem.CANFDX | ZModem.CANOVIO | ZModem.CANFC32, 0));
                new ZModemSession().receive(localToRemote.input(), remoteToLocal.output(),
                        remoteDir, new NopListener());
            } catch (Throwable t) {
                support.failures.add(t);
            }
        }, "remote-rz");
        remote.start();

        feedTerminalLoop(stream, interceptor);
        remote.join(10_000);
        remoteToLocal.close();
        localToRemote.close();

        assertTrue(support.failures.isEmpty(), () -> "remote failures: " + support.failures);
        assertArrayEquals(content, Files.readAllBytes(remoteDir.resolve("upload.bin")));
        assertTrue(support.finishedSuccess, "upload session should finish successfully");
    }

    /** 普通终端输出不能误触发。 */
    @Test
    void normalOutputDoesNotTrigger() throws Exception {
        Channel toLocal = new Channel();
        Channel fromLocal = new Channel();
        byte[] text = "hello ** world, no zmodem here\r\n".getBytes();
        toLocal.output().write(text, 0, text.length);
        toLocal.close();

        ZModemStream stream = new ZModemStream(toLocal.input());
        StubSupport support = new StubSupport(tempDir.resolve("none"));
        ZModemInterceptor interceptor = new ZModemInterceptor(stream, fromLocal.output(), support);

        int terminalBytes = 0;
        int b;
        while ((b = stream.read()) >= 0) {
            if (!interceptor.onRawByte(b)) {
                terminalBytes++;
            }
        }
        assertEquals(text.length, terminalBytes);
        assertEquals(0, support.startedCount);
    }

    /** 用户取消发送选择时应发出取消序列并正常结束。 */
    @Test
    void declinedUploadCancelsSession() throws Exception {
        Channel remoteToLocal = new Channel();
        Channel localToRemote = new Channel();
        ZModemStream stream = new ZModemStream(remoteToLocal.input());
        StubSupport support = new StubSupport(tempDir.resolve("none2"));
        ZModemInterceptor interceptor = new ZModemInterceptor(stream, localToRemote.output(), support);
        support.filesToSend = List.of();   // 用户取消

        ZModemCodec.writeHexHeader(remoteToLocal.output(), ZModem.ZRINIT, ZModemHeader.flagsData(0x23, 0));
        remoteToLocal.close();

        feedTerminalLoop(stream, interceptor);
        assertFalse(support.finishedSuccess);
        assertNotNull(support.lastMessage);
    }

    /** 模拟终端读取循环：逐字节喂给拦截器，直到会话完成或流结束。返回送往终端的字节数。 */
    private static int feedTerminalLoop(ZModemStream stream, ZModemInterceptor interceptor) throws IOException {
        int terminalBytes = 0;
        while (true) {
            int b = stream.read();
            if (b < 0) {
                return terminalBytes;
            }
            if (interceptor.onRawByte(b)) {
                return terminalBytes;
            }
            terminalBytes++;
        }
    }

    private static final class NopListener implements ZModemProgressListener {
        @Override
        public void onFileStart(String name, long size) {
        }

        @Override
        public void onProgress(long bytes) {
        }

        @Override
        public void onFileDone(String name, long size) {
        }
    }

    private static final class StubSupport implements ZModemSupport {
        final Path downloadDir;
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        volatile List<Path> filesToSend = List.of();
        volatile int startedCount;
        volatile boolean finishedSuccess;
        volatile String lastMessage;
        private volatile boolean cancelled;

        StubSupport(Path downloadDir) {
            this.downloadDir = downloadDir;
        }

        @Override
        public Path downloadDirectory() {
            return downloadDir;
        }

        @Override
        public List<Path> chooseFilesToSend() {
            return filesToSend;
        }

        @Override
        public void onFileStart(String direction, String fileName, long size) {
            startedCount++;
        }

        @Override
        public void onProgress(long transferred, long total) {
        }

        @Override
        public void onFileDone(String fileName, boolean success) {
        }

        @Override
        public void onSessionFinished(boolean success, String message) {
            finishedSuccess = success;
            lastMessage = message;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void resetCancel() {
            cancelled = false;
        }
    }

    /** 与 ZModemSessionTest 相同的阻塞通道（不依赖线程存活判定）。 */
    private static final class Channel {
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile boolean closed;

        InputStream input() {
            return new InputStream() {
                private byte[] current;
                private int position;

                @Override
                public int read() throws IOException {
                    if (!ensure()) {
                        return -1;
                    }
                    return current[position++] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) {
                        return 0;
                    }
                    if (!ensure()) {
                        return -1;
                    }
                    int n = Math.min(len, current.length - position);
                    System.arraycopy(current, position, b, off, n);
                    position += n;
                    return n;
                }

                private boolean ensure() throws IOException {
                    while (current == null || position >= current.length) {
                        byte[] next = take();
                        if (next == null) {
                            return false;
                        }
                        current = next;
                        position = 0;
                    }
                    return true;
                }
            };
        }

        OutputStream output() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    if (!closed) {
                        queue.add(new byte[] {(byte) b});
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    if (!closed && len > 0) {
                        queue.add(Arrays.copyOfRange(b, off, off + len));
                    }
                }
            };
        }

        void close() {
            closed = true;
            queue.add(new byte[0]);
        }

        private byte[] take() throws IOException {
            try {
                byte[] chunk = queue.take();
                return chunk.length == 0 ? null : chunk;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for data", e);
            }
        }
    }
}
