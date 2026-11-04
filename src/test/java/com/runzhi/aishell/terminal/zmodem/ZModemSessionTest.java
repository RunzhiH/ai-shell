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

class ZModemSessionTest {

    @TempDir
    Path tempDir;

    /** 双端闭环：发送方（sz 角色）与接收方（rz 角色）直连，多文件批量传输。 */
    @Test
    void loopbackTransferTwoFiles() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Path outDir = tempDir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);

        byte[] big = trickyPayload(300_000);
        Files.write(srcDir.resolve("hello.bin"), big);
        Files.write(srcDir.resolve("empty.txt"), new byte[0]);
        Files.write(srcDir.resolve("tiny.txt"), "AIShell zmodem\r\n".getBytes());

        TransferStats stats = new TransferStats();
        Channel toReceiver = new Channel();
        Channel toSender = new Channel();

        Thread receiverThread = new Thread(() -> {
            try {
                new ZModemSession().receive(toReceiver.input(), toSender.output(), outDir, stats);
            } catch (Throwable t) {
                stats.failures.add(t);
            }
        }, "zmodem-receiver");

        Thread senderThread = new Thread(() -> {
            try {
                new ZModemSession().send(toSender.input(), toReceiver.output(),
                        List.of(ZModemOutFile.of(srcDir.resolve("hello.bin")),
                                ZModemOutFile.of(srcDir.resolve("empty.txt")),
                                ZModemOutFile.of(srcDir.resolve("tiny.txt"))),
                        stats);
            } catch (Throwable t) {
                stats.failures.add(t);
            }
        }, "zmodem-sender");

        receiverThread.start();
        senderThread.start();
        senderThread.join(30_000);
        receiverThread.join(30_000);
        toReceiver.close();
        toSender.close();

        assertTrue(stats.failures.isEmpty(), () -> "session failures: " + stats.failures);
        assertFalse(senderThread.isAlive(), "sender did not finish");
        assertFalse(receiverThread.isAlive(), "receiver did not finish");
        assertArrayEquals(big, Files.readAllBytes(outDir.resolve("hello.bin")));
        assertEquals(0, Files.size(outDir.resolve("empty.txt")));
        assertArrayEquals("AIShell zmodem\r\n".getBytes(), Files.readAllBytes(outDir.resolve("tiny.txt")));
        // 发送端（发完一个文件）与接收端（写完一个文件）共用同一个 listener，各回调 3 次
        assertEquals(6, stats.fileDoneCount);
    }

    /** CRC16 路径（接收方不声明 CANFC32）。 */
    @Test
    void loopbackTransferCrc16() throws Exception {
        Path srcDir = tempDir.resolve("src16");
        Path outDir = tempDir.resolve("out16");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);

        byte[] payload = trickyPayload(50_000);
        Files.write(srcDir.resolve("crc16.bin"), payload);

        TransferStats stats = new TransferStats();
        Channel toReceiver = new Channel();
        Channel toSender = new Channel();

        Thread receiverThread = new Thread(() -> {
            try {
                new ZModemSession().receive(toReceiver.input(), toSender.output(), outDir, stats, 0);
            } catch (Throwable t) {
                stats.failures.add(t);
            }
        }, "zmodem-receiver16");

        Thread senderThread = new Thread(() -> {
            try {
                new ZModemSession().send(toSender.input(), toReceiver.output(),
                        List.of(ZModemOutFile.of(srcDir.resolve("crc16.bin"))), stats);
            } catch (Throwable t) {
                stats.failures.add(t);
            }
        }, "zmodem-sender16");

        receiverThread.start();
        senderThread.start();
        senderThread.join(30_000);
        receiverThread.join(30_000);
        toReceiver.close();
        toSender.close();

        assertTrue(stats.failures.isEmpty(), () -> "failures: " + stats.failures);
        assertArrayEquals(payload, Files.readAllBytes(outDir.resolve("crc16.bin")));
    }

    /** 发起邀请的首帧必须是可自动检测的 ZMODEM 前导（** ZDLE B ...）。 */
    @Test
    void invitationStartsDetectableHexPreamble() throws Exception {
        TransferStats stats = new TransferStats();
        Channel toReceiver = new Channel();
        Channel toSender = new Channel();

        Thread senderThread = new Thread(() -> {
            try {
                new ZModemSession().send(toSender.input(), toReceiver.output(), List.of(), stats);
            } catch (Exception | Error ignored) {
                // 会话被打断属预期
            }
        });
        senderThread.start();

        InputStream receiverSide = toReceiver.input();
        byte[] first = new byte[4];
        for (int i = 0; i < 4; i++) {
            first[i] = (byte) receiverSide.read();
        }
        toReceiver.close();
        senderThread.join(5_000);

        assertEquals('*', first[0]);
        assertEquals('*', first[1]);
        assertEquals(0x18, first[2]);
        assertEquals('B', first[3]);
    }

    @Test
    void parsesYmodemStyleFileInfo() {
        byte[] raw = ("data.tar.gz" + (char) 0 + "12345 1716547200 100644 0 12345").getBytes();
        String[] info = ZModemSession.parseFileInfo(raw);
        assertEquals("data.tar.gz", info[0]);
        assertEquals("12345", info[1]);
    }

    @Test
    void rejectsPathTraversalInFileName() {
        Path dir = tempDir;
        assertEquals(dir.resolve("etc.zip"), ZModemSession.safeTarget(dir, "../../etc.zip"));
        assertEquals(dir.resolve("sub.zip"), ZModemSession.safeTarget(dir, "/tmp/sub.zip"));
        assertEquals(dir.resolve("unnamed"), ZModemSession.safeTarget(dir, ""));
    }

    /** 含全部字节值与大量 ZDLE/CR/XON 的负载数据。 */
    private static byte[] trickyPayload(int size) {
        byte[] data = new byte[size];
        Random random = new Random(42);
        for (int i = 0; i < size; i++) {
            int mod = i % 16;
            if (mod == 0) data[i] = 0x18;          // ZDLE
            else if (mod == 1) data[i] = 0x0d;     // CR
            else if (mod == 2) data[i] = 0x11;     // XON
            else if (mod == 3) data[i] = 0x13;     // XOFF
            else if (mod == 4) data[i] = 0x7f;     // RUB0
            else if (mod == 5) data[i] = (byte) 0xff;   // RUB1
            else if (mod == 6) data[i] = '*';
            else data[i] = (byte) random.nextInt(256);
        }
        return data;
    }

    private static class TransferStats implements ZModemProgressListener {
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        volatile int fileDoneCount;

        @Override
        public void onFileStart(String name, long size) {
        }

        @Override
        public void onProgress(long bytes) {
        }

        @Override
        public void onFileDone(String name, long size) {
            fileDoneCount++;
        }
    }

    /**
     * 单向字节通道：不依赖线程存活判定（避免 PipedInputStream 的 "Read end dead"），
     * 读取端阻塞等待数据，close() 后读到 EOF。
     */
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
