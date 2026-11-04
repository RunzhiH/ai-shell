/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * ZMODEM 会话：在已建立的终端字节流上做文件传输（rz/sz 角色）。
 * <p>
 * {@link #receive} 在对端运行 sz（对端为发送方）时调用，我们扮演 rz；
 * {@link #send} 在对端运行 rz（对端为接收方）时调用，我们扮演 sz。
 * 两个方法都在调用线程上阻塞，直到会话结束。失败抛 IOException（含
 * {@link ZModemCancelledException}）；成功（含对端 ZFIN）正常返回。
 * <p>
 * 引擎内部不做超时；取消由 {@link #cancel()} 中断会话线程（或外部关闭流）实现。
 * <p>
 * 参考实现：lrzsz 与《The ZMODEM Inter Application File Transfer Protocol》(Forsberg)。
 */
public final class ZModemSession {

    private static final int ZRINIT_CAPS = ZModem.CANFDX | ZModem.CANOVIO | ZModem.CANFC32;
    private static final int MAX_HEADER_RETRIES = 4;
    private static final int SUBPACKET_SIZE = ZModem.MAX_SUBPACKET;

    private final Thread sessionThread = Thread.currentThread();
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        sessionThread.interrupt();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ==================== 接收（对端 sz，我们 rz） ====================

    public void receive(InputStream in, OutputStream out, Path downloadDir,
                        ZModemProgressListener listener) throws IOException {
        receive(in, out, downloadDir, listener, ZRINIT_CAPS);
    }

    /** 指定 ZRINIT 能力位（测试用 0 强制 CRC16 路径）。 */
    void receive(InputStream in, OutputStream out, Path downloadDir,
                 ZModemProgressListener listener, int caps) throws IOException {
        boolean handshakeDone = false;
        main:
        while (true) {
            ZModemHeader h;
            try {
                h = ZModemCodec.readHeader(in);
            } catch (ZModemCancelledException e) {
                throw e;
            }
            switch (h.type()) {
                case ZModem.ZRQINIT, ZModem.ZFILE, ZModem.ZSINIT -> {
                    if (!handshakeDone) {
                        ZModemCodec.writeHexHeader(out, ZModem.ZRINIT,
                                ZModemHeader.flagsData(caps, 0));
                        handshakeDone = true;
                    }
                    if (h.type() == ZModem.ZSINIT) {
                        // 对端声明 Attn 串：读取其后 ZCRCW 子包并回 ZACK（内容不解析）
                        readInfoSubpacket(in, h);
                        ZModemCodec.writeHexHeader(out, ZModem.ZACK, ZModemHeader.positionData(0));
                    } else if (h.type() == ZModem.ZFILE) {
                        receiveFile(in, out, downloadDir, listener, h);
                    }
                }
                case ZModem.ZFIN -> {
                    ZModemCodec.writeHexHeader(out, ZModem.ZFIN, ZModemHeader.positionData(0));
                    break main;
                }
                case ZModem.ZCAN, ZModem.ZABORT, ZModem.ZFERR -> throw new ZModemCancelledException();
                default -> {
                    // 未预期的帧：回 ZNAK 让对端重发
                    ZModemCodec.writeHexHeader(out, ZModem.ZNAK, ZModemHeader.positionData(0));
                }
            }
        }
    }

    private void receiveFile(InputStream in, OutputStream out, Path downloadDir,
                             ZModemProgressListener listener, ZModemHeader fileHeader) throws IOException {
        String[] info = parseFileInfo(readInfoSubpacket(in, fileHeader));
        String name = info[0];
        long size = info[1].isEmpty() ? -1 : parseUnsigned(info[1]);
        Path target = safeTarget(downloadDir, name);
        listener.onFileStart(target.getFileName().toString(), size);

        ZModemCodec.writeHexHeader(out, ZModem.ZRPOS, ZModemHeader.positionData(0));
        long offset = 0;
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(0);
            while (true) {
                ZModemHeader h = ZModemCodec.readHeader(in);
                if (h.type() != ZModem.ZDATA) {
                    if (h.type() == ZModem.ZEOF && offset == h.position()) {
                        listener.onFileDone(target.getFileName().toString(), offset);
                        ZModemCodec.writeHexHeader(out, ZModem.ZRINIT, ZModemHeader.flagsData(ZRINIT_CAPS, 0));
                        return;
                    }
                    if (h.type() == ZModem.ZCAN || h.type() == ZModem.ZFERR) {
                        throw new ZModemCancelledException();
                    }
                    ZModemCodec.writeHexHeader(out, ZModem.ZRPOS, ZModemHeader.positionData(offset));
                    continue;
                }
                // ZDATA：CRC 模式以本帧头为准（ZBIN=CRC16 / ZBIN32=CRC32），不能沿用 ZFILE 头
                boolean frameCrc32 = h.frameIndicator() == ZModem.ZBIN32;
                if (h.position() != offset) {
                    offset = h.position();
                    raf.seek(offset);
                }
                if (!receiveData(in, out, raf, frameCrc32, offset)) {
                    // 子包 CRC 错：让对方从当前偏移重发
                    ZModemCodec.writeHexHeader(out, ZModem.ZRPOS, ZModemHeader.positionData(offset));
                }
                // 子包循环已把数据写入文件，偏移以文件指针为准
                offset = raf.getFilePointer();
                listener.onProgress(offset);
                if (cancelled) {
                    throw new ZModemCancelledException();
                }
                // ZEOF/ZRINIT 等待下一个头；循环回去读头
            }
        }
    }

    /**
     * 读取 ZDATA 后的数据子包序列，直到 ZCRCE/ZCRCW（帧结束）。
     * 返回 true 表示正常结束；false 表示出现 CRC 错误（调用方应回 ZRPOS）。
     */
    private boolean receiveData(InputStream in, OutputStream out, RandomAccessFile raf,
                                boolean useCrc32, long startOffset) throws IOException {
        long offset = startOffset;
        while (true) {
            ZModemCodec.Subpacket p = ZModemCodec.readSubpacket(in, useCrc32);
            if (!p.valid()) {
                return false;
            }
            if (p.data().length > 0) {
                raf.write(p.data());
                offset += p.data().length;
            }
            switch (p.frameEnd()) {
                case ZModem.ZCRCG -> { /* 流式继续 */ }
                case ZModem.ZCRCQ -> ZModemCodec.writeHexHeader(out, ZModem.ZACK,
                        ZModemHeader.positionData(offset));
                case ZModem.ZCRCW -> {
                    ZModemCodec.writeHexHeader(out, ZModem.ZACK, ZModemHeader.positionData(offset));
                    return true;
                }
                case ZModem.ZCRCE -> {
                    return true;
                }
                default -> throw new IOException("unexpected subpacket end: " + p.frameEnd());
            }
        }
    }

    // ==================== 发送（对端 rz，我们 sz） ====================

    public void send(InputStream in, OutputStream out, List<ZModemOutFile> files,
                     ZModemProgressListener listener) throws IOException {
        send(in, out, files, listener, -1);
    }

    /**
     * @param peerCaps 对端 ZRINIT 的能力位；&gt;= 0 表示对端已就绪（对端在跑 rz），
     *                 跳过 ZRQINIT 邀请直接发文件；-1 表示需要先邀请。
     */
    public void send(InputStream in, OutputStream out, List<ZModemOutFile> files,
                     ZModemProgressListener listener, int peerCaps) throws IOException {
        boolean useCrc32 = false;
        if (peerCaps >= 0) {
            useCrc32 = (peerCaps & ZModem.CANFC32) != 0;
        } else {
            useCrc32 = (inviteReceiver(in, out) & ZModem.CANFC32) != 0;
        }

        for (ZModemOutFile file : files) {
            sendFile(in, out, file, useCrc32, listener);
            if (cancelled) {
                throw new ZModemCancelledException();
            }
        }
        ZModemCodec.writeHexHeader(out, ZModem.ZFIN, ZModemHeader.positionData(0));
        // 等对端 ZFIN（也可能先收到别的，忽略直到 ZFIN 或取消）
        while (true) {
            try {
                ZModemHeader fin = ZModemCodec.readHeader(in);
                if (fin.type() == ZModem.ZFIN || fin.type() == ZModem.ZCAN) {
                    out.write('O');
                    out.write('O');
                    out.flush();
                    return;
                }
            } catch (ZModemCancelledException e) {
                throw e;
            }
        }
    }

    /** 发送 ZRQINIT 邀请，等待对端 ZRINIT，返回对端能力位。 */
    private int inviteReceiver(InputStream in, OutputStream out) throws IOException {
        int retries = 0;
        while (true) {
            if (++retries > MAX_HEADER_RETRIES) {
                throw new IOException("no ZRINIT from remote after retries");
            }
            ZModemCodec.writeHexHeader(out, ZModem.ZRQINIT, ZModemHeader.positionData(0));
            ZModemHeader h;
            try {
                h = ZModemCodec.readHeader(in);
            } catch (ZModemCancelledException e) {
                throw e;
            }
            switch (h.type()) {
                case ZModem.ZRINIT -> {
                    return h.flagsLow();
                }
                case ZModem.ZCHALLENGE -> ZModemCodec.writeHexHeader(out, ZModem.ZACK, h.data());
                case ZModem.ZFIN, ZModem.ZCAN -> throw new ZModemCancelledException();
                default -> { /* 忽略，下一轮重发邀请 */ }
            }
        }
    }

    private void sendFile(InputStream in, OutputStream out, ZModemOutFile file,
                          boolean useCrc32, ZModemProgressListener listener) throws IOException {
        listener.onFileStart(file.remoteName(), file.size());
        long sent = 0;
        int zfileRetries = 0;
        // ZFILE 头 + ZCRCW 文件信息子包（YMODEM block0 格式），一个文件只声明一次
        ZModemCodec.writeHexHeader(out, ZModem.ZFILE,
                ZModemHeader.flagsData(ZModem.ZCBIN, 0));
        byte[] info = fileInfoBytes(file);
        ZModemCodec.writeSubpacket(out, info, 0, info.length, ZModem.ZCRCW, false);

        while (true) {
            ZModemHeader h;
            try {
                h = ZModemCodec.readHeader(in);
            } catch (ZModemCancelledException e) {
                throw e;
            }
            switch (h.type()) {
                case ZModem.ZRPOS -> {
                    sent = sendData(in, out, file, h.position(), useCrc32, listener);
                    listener.onFileDone(file.remoteName(), sent);
                    return;
                }
                case ZModem.ZSKIP -> {
                    listener.onFileDone(file.remoteName(), 0);
                    return;
                }
                case ZModem.ZRINIT -> { /* 对端重发能力声明：忽略，继续等 ZRPOS */ }
                case ZModem.ZNAK -> {
                    if (++zfileRetries > MAX_HEADER_RETRIES) {
                        throw new IOException("remote rejected ZFILE");
                    }
                    ZModemCodec.writeHexHeader(out, ZModem.ZFILE,
                            ZModemHeader.flagsData(ZModem.ZCBIN, 0));
                    ZModemCodec.writeSubpacket(out, info, 0, info.length, ZModem.ZCRCW, false);
                }
                case ZModem.ZCAN, ZModem.ZFERR, ZModem.ZABORT, ZModem.ZFIN -> throw new ZModemCancelledException();
                default -> {
                    if (++zfileRetries > MAX_HEADER_RETRIES) {
                        throw new IOException("unexpected header " + ZModem.frameName(h.type())
                                + " while waiting for ZRPOS");
                    }
                }
            }
        }
    }

    /** 从 fromOffset 开始发送文件数据，返回最终发送字节数。 */
    private long sendData(InputStream in, OutputStream out, ZModemOutFile file, long fromOffset,
                          boolean useCrc32, ZModemProgressListener listener) throws IOException {
        long offset = fromOffset;
        byte[] buf = new byte[SUBPACKET_SIZE];
        int eofRetries = 0;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.path().toFile(), "r")) {
            raf.seek(offset);
            long fileLength = raf.length();
            // 一个数据帧只有一个 ZDATA 头，其后连续子包以 ZCRCE 结束；没有数据要发时直接 ZEOF
            if (offset < fileLength) {
                ZModemCodec.writeBinaryHeader(out, ZModem.ZDATA, ZModemHeader.positionData(offset), useCrc32);
            }
            while (true) {
                if (cancelled) {
                    throw new ZModemCancelledException();
                }
                int n = offset < fileLength ? raf.read(buf) : -1;
                if (n < 0) {
                    // 文件读到末尾：ZEOF 位置为实际已发送字节数（与对端核对）
                    ZModemCodec.writeHexHeader(out, ZModem.ZEOF, ZModemHeader.positionData(offset));
                    // 等 ZRINIT（本文件完成）或 ZRPOS（重传）
                    while (true) {
                        ZModemHeader h;
                        try {
                            h = ZModemCodec.readHeader(in);
                        } catch (ZModemCancelledException e) {
                            throw e;
                        }
                        if (h.type() == ZModem.ZRINIT) {
                            return offset;
                        }
                        if (h.type() == ZModem.ZRPOS) {
                            offset = h.position();
                            raf.seek(offset);
                            // 重传需要新的 ZDATA 头
                            if (offset < fileLength) {
                                ZModemCodec.writeBinaryHeader(out, ZModem.ZDATA,
                                        ZModemHeader.positionData(offset), useCrc32);
                            }
                            break;   // 重回数据循环
                        }
                        if (h.type() == ZModem.ZNAK) {
                            if (++eofRetries > MAX_HEADER_RETRIES) {
                                throw new IOException("remote repeated ZNAK on ZEOF");
                            }
                            ZModemCodec.writeHexHeader(out, ZModem.ZEOF, ZModemHeader.positionData(offset));
                            continue;
                        }
                        if (h.type() == ZModem.ZCAN || h.type() == ZModem.ZFIN) {
                            throw new ZModemCancelledException();
                        }
                    }
                    continue;
                }
                // 一帧的最后一个子包用 ZCRCE 结束，接收端据此回到读头模式接收 ZEOF
                boolean last = offset + n >= fileLength;
                int frameEnd = last ? ZModem.ZCRCE : ZModem.ZCRCG;
                ZModemCodec.writeSubpacket(out, buf, 0, n, frameEnd, useCrc32);
                offset += n;
                listener.onProgress(offset);
            }
        }
    }

    // ==================== 工具 ====================

    private static byte[] readInfoSubpacket(InputStream in, ZModemHeader header) throws IOException {
        ZModemCodec.Subpacket p = ZModemCodec.readSubpacket(in, header.frameIndicator() == ZModem.ZBIN32);
        return p.data();
    }

    /** 解析 ZFILE 信息子包：文件名\0 + 十进制大小 + 八进制时间戳等（空格分隔）。 */
    static String[] parseFileInfo(byte[] raw) {
        int nul = 0;
        while (nul < raw.length && raw[nul] != 0) {
            nul++;
        }
        String name = new String(raw, 0, nul, StandardCharsets.UTF_8);
        String rest = nul + 1 < raw.length
                ? new String(raw, nul + 1, raw.length - nul - 1, StandardCharsets.UTF_8).trim()
                : "";
        String[] fields = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        String size = fields.length > 0 ? fields[0] : "";
        return new String[] {name, size};
    }

    /** 生成 ZFILE 信息子包字节：name\0size mtime mode serial remaining。 */
    private static byte[] fileInfoBytes(ZModemOutFile file) {
        long mtime = file.mtimeMillis() / 1000;
        long mode = 0100644;
        String text = file.remoteName() + (char) 0
                + (file.size() < 0 ? "0" : file.size()) + ' '
                + Long.toOctalString(mtime) + ' '
                + Long.toOctalString(mode) + ' '
                + 0 + ' '
                + (file.size() < 0 ? 0 : file.size());
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 防路径穿越：只保留文件名部分，去掉分隔符与 ..。 */
    static Path safeTarget(Path dir, String remoteName) {
        if (remoteName == null || remoteName.isBlank()) {
            return dir.resolve("unnamed");
        }
        String base = remoteName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.trim();
        if (base.equals("..") || base.equals(".") || base.isEmpty()) {
            base = "unnamed";
        }
        return dir.resolve(base);
    }

    private static long parseUnsigned(String decimal) {
        try {
            return Long.parseUnsignedLong(decimal);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
