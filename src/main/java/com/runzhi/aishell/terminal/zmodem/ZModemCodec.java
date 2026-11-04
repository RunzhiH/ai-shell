/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * ZMODEM 链路层编解码：CRC、ZDLE 转义、帧（头）与数据子包的读写。
 * <p>
 * 帧格式（规范 §7.3）：
 * <ul>
 *   <li>二进制头：ZPAD ZDLE(ZBIN|ZBIN32) 类型 F3/P0 F2/P1 F1/P2 F0/P3 CRC</li>
 *   <li>十六进制头：ZPAD ZPAD ZDLE ZHEX + 每字节两位十六进制(小写) + CR LF + XON</li>
 * </ul>
 * 数据子包：0..1024 字节数据（ZDLE 转义）+ ZDLE(ZCRCE|ZCRCG|ZCRCQ|ZCRCW) + CRC（覆盖数据与结束符）。
 * <p>
 * CRC16 为 XMODEM CRC（多项式 0x1021，初值 0，无最终异或）；CRC32 为标准 CRC-32（补码发送、大端）。
 * <p>
 * 注意：CAN 与 ZDLE 同为 0x18，流中连续 5 个原始 0x18 即取消序列（ZModemCancelledException）。
 */
public final class ZModemCodec {

    /** 数据子包读取结果。valid 为 false 表示 CRC 校验失败，调用方应回 ZRPOS。 */
    public record Subpacket(byte[] data, int frameEnd, boolean valid) {
    }

    private ZModemCodec() {
    }

    // ---- CRC16（XMODEM，表驱动） ----

    private static final int[] CRC16_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xffff;
            }
            CRC16_TABLE[i] = crc;
        }
    }

    public static int crc16Update(int crc, int b) {
        return ((crc << 8) ^ CRC16_TABLE[((crc >>> 8) ^ b) & 0xff]) & 0xffff;
    }

    /** 对整型数组（头部的类型与 4 字节数据）计算 CRC16。 */
    public static int crc16Update(int crc, int[] data, int off, int len) {
        for (int i = 0; i < len; i++) {
            crc = crc16Update(crc, data[off + i] & 0xff);
        }
        return crc;
    }

    public static int crc16(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc = crc16Update(crc, data[off + i] & 0xff);
        }
        return crc;
    }

    // ---- ZDLE 转义（发送侧，规范 §7.2） ----

    /** 必须转义的字节：ZDLE、0x10/0x11/0x13 及其高位变体 0x90/0x91/0x93。 */
    private static boolean mustEscape(int b) {
        return b == 0x18 || b == 0x10 || b == 0x11 || b == 0x13
                || b == 0x90 || b == 0x91 || b == 0x93;
    }

    /**
     * 写出一个转义后的字节；0x7F/0xFF 转义为 ZRUB0/ZRUB1（接收方总是识别）。
     * 入参先做 8 位掩码，避免调用方传入的整数高位污染判断。
     */
    static void writeEscaped(OutputStream out, int b) throws IOException {
        b &= 0xff;
        if (b == 0x7f) {
            out.write(ZModem.ZDLE);
            out.write(ZModem.ZRUB0);
        } else if (b == 0xff) {
            out.write(ZModem.ZDLE);
            out.write(ZModem.ZRUB1);
        } else if (mustEscape(b)) {
            out.write(ZModem.ZDLE);
            out.write((b & 0xff) ^ 0x40);
        } else {
            out.write(b & 0xff);
        }
    }

    // ---- 头部写出 ----

    /** 十六进制头：** ZDLE B + 类型与 4 字节数据与 CRC16（各两位小写十六进制）+ CR LF XON。 */
    public static void writeHexHeader(OutputStream out, int type, int[] data) throws IOException {
        if (data.length != 4) {
            throw new IllegalArgumentException("header data must be 4 bytes");
        }
        int crc = crc16Update(crc16Update(0, type), data, 0, 4);
        StringBuilder sb = new StringBuilder(24);
        sb.append("**").append((char) ZModem.ZDLE).append((char) ZModem.ZHEX);
        appendHex(sb, type);
        for (int d : data) {
            appendHex(sb, d);
        }
        appendHex(sb, (crc >>> 8) & 0xff);
        appendHex(sb, crc & 0xff);
        sb.append('\r').append('\n').append((char) 0x11);   // XON
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void appendHex(StringBuilder sb, int v) {
        sb.append(Character.forDigit((v >>> 4) & 0xf, 16));
        sb.append(Character.forDigit(v & 0xf, 16));
    }

    /** 二进制头（ZBIN 或 ZBIN32），类型与数据均转义，CRC 大端写出（转义）。 */
    public static void writeBinaryHeader(OutputStream out, int type, int[] data, boolean useCrc32)
            throws IOException {
        if (data.length != 4) {
            throw new IllegalArgumentException("header data must be 4 bytes");
        }
        out.write(ZModem.ZPAD);
        out.write(ZModem.ZDLE);
        out.write(useCrc32 ? ZModem.ZBIN32 : ZModem.ZBIN);
        if (useCrc32) {
            CRC32 crc = new CRC32();
            crc.update(type);
            for (int d : data) {
                crc.update(d);
            }
            long v = crc.getValue();
            writeEscaped(out, type);
            for (int d : data) {
                writeEscaped(out, d);
            }
            writeEscaped(out, (int) (v >>> 24));
            writeEscaped(out, (int) (v >>> 16));
            writeEscaped(out, (int) (v >>> 8));
            writeEscaped(out, (int) v);
        } else {
            int crc = crc16Update(0, type);
            crc = crc16Update(crc, data, 0, 4);
            writeEscaped(out, type);
            for (int d : data) {
                writeEscaped(out, d);
            }
            writeEscaped(out, (crc >>> 8) & 0xff);
            writeEscaped(out, crc & 0xff);
        }
        out.flush();
    }

    /** 数据子包：数据（转义）+ ZDLE + 结束符 + CRC（覆盖数据与结束符，转义，大端）。 */
    public static void writeSubpacket(OutputStream out, byte[] data, int off, int len,
                                      int frameEnd, boolean useCrc32) throws IOException {
        if (useCrc32) {
            CRC32 crc = new CRC32();
            crc.update(data, off, len);
            crc.update(frameEnd);
            long v = crc.getValue();
            writeEscapedData(out, data, off, len);
            out.write(ZModem.ZDLE);
            out.write(frameEnd);
            writeEscaped(out, (int) (v >>> 24));
            writeEscaped(out, (int) (v >>> 16));
            writeEscaped(out, (int) (v >>> 8));
            writeEscaped(out, (int) v);
        } else {
            int crc = 0;
            for (int i = 0; i < len; i++) {
                crc = crc16Update(crc, data[off + i] & 0xff);
            }
            crc = crc16Update(crc, frameEnd);
            writeEscapedData(out, data, off, len);
            out.write(ZModem.ZDLE);
            out.write(frameEnd);
            writeEscaped(out, (crc >>> 8) & 0xff);
            writeEscaped(out, crc & 0xff);
        }
        out.flush();
    }

    private static void writeEscapedData(OutputStream out, byte[] data, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            writeEscaped(out, data[off + i] & 0xff);
        }
    }

    /** 取消序列：8 个 CAN + 10 个退格。 */
    public static void writeCancel(OutputStream out) throws IOException {
        for (int i = 0; i < ZModem.CANCEL_RETRY; i++) {
            out.write(0x18);   // CAN
        }
        for (int i = 0; i < ZModem.CANCEL_BACKSPACE; i++) {
            out.write(0x08);   // BS
        }
        out.flush();
    }

    // ---- 头部读取 ----

    /**
     * 从流中读取并解析下一个帧头（二进制或十六进制均可）。
     * 跳过非帧数据；连续 5 个 CAN 抛出 {@link ZModemCancelledException}。
     */
    public static ZModemHeader readHeader(InputStream in) throws IOException, ZModemCancelledException {
        int canRun = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("stream closed while waiting for header");
            }
            if (b == 0x18) {
                canRun++;
                if (canRun >= 5) {
                    throw new ZModemCancelledException();
                }
                continue;
            }
            canRun = 0;
            if (b != ZModem.ZPAD) {
                continue;
            }
            b = in.read();
            if (b < 0) {
                throw new EOFException("stream closed after ZPAD");
            }
            boolean hex = b == ZModem.ZPAD;
            if (!hex && b != ZModem.ZDLE) {
                continue;   // 单 ZPAD 后不是 ZDLE，重新找
            }
            int indicator;
            if (hex) {
                b = in.read();
                if (b != ZModem.ZDLE) {
                    continue;
                }
                indicator = in.read();
            } else {
                indicator = in.read();
            }
            if (indicator < 0) {
                throw new EOFException("stream closed while reading frame indicator");
            }
            if (indicator == ZModem.ZBIN || indicator == ZModem.ZBIN32) {
                ZModemHeader h = readBinaryHeader(in, indicator);
                if (h != null) {
                    return h;
                }
            } else if (indicator == ZModem.ZHEX) {
                ZModemHeader h = readHexHeader(in);
                if (h != null) {
                    return h;
                }
            }
            // CRC 校验失败则继续找下一个帧头（对端会重发）
        }
    }

    private static ZModemHeader readBinaryHeader(InputStream in, int indicator) throws IOException {
        boolean useCrc32 = indicator == ZModem.ZBIN32;
        int type = readEscaped(in);
        if (type < 0) {
            return null;
        }
        int[] data = new int[4];
        for (int i = 0; i < 4; i++) {
            data[i] = readEscaped(in);
            if (data[i] < 0) {
                return null;
            }
        }
        if (useCrc32) {
            CRC32 crc = new CRC32();
            crc.update(type);
            for (int d : data) {
                crc.update(d);
            }
            long expected = crc.getValue();
            long got = (((long) readEscaped(in) & 0xffL) << 24)
                    | (((long) readEscaped(in) & 0xffL) << 16)
                    | (((long) readEscaped(in) & 0xffL) << 8)
                    | (readEscaped(in) & 0xffL);
            return got == expected ? new ZModemHeader(indicator, type, data) : null;
        }
        int crc = crc16Update(0, type);
        crc = crc16Update(crc, data, 0, 4);
        int hi = readEscaped(in);
        int lo = readEscaped(in);
        if (hi < 0 || lo < 0) {
            return null;
        }
        int got = ((hi & 0xff) << 8) | (lo & 0xff);
        return got == crc ? new ZModemHeader(indicator, type, data) : null;
    }

    private static ZModemHeader readHexHeader(InputStream in) throws IOException {
        int type = readHexByte(in);
        if (type < 0) {
            return null;
        }
        int[] data = new int[4];
        for (int i = 0; i < 4; i++) {
            data[i] = readHexByte(in);
            if (data[i] < 0) {
                return null;
            }
        }
        int crcHi = readHexByte(in);
        int crcLo = readHexByte(in);
        if (crcHi < 0 || crcLo < 0) {
            return null;
        }
        int got = (crcHi << 8) | crcLo;
        int crc = crc16Update(crc16Update(0, type), data, 0, 4);
        if (got != crc) {
            return null;
        }
        skipCrlf(in);
        return new ZModemHeader(ZModem.ZHEX, type, data);
    }

    /** 十六进制头尾的 CR LF 必须消费；XON 不主动读（留在流里，下个读头循环会跳过）。 */
    private static void skipCrlf(InputStream in) throws IOException {
        int first = in.read();
        if (first == '\r') {
            in.read();   // LF（规范：CR 后期望两个字符）
        }
    }

    private static int readHexByte(InputStream in) throws IOException {
        int hi = readHexDigit(in);
        if (hi < 0) {
            return -1;
        }
        int lo = readHexDigit(in);
        if (lo < 0) {
            return -1;
        }
        return (hi << 4) | lo;
    }

    private static int readHexDigit(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            return -1;
        }
        int d = Character.digit((char) b, 16);
        return d >= 0 ? d : -1;
    }

    /** 读取一个 ZDLE 解码后的字节；返回 -1 表示出现了非预期转义序列。 */
    static int readEscaped(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            return -1;
        }
        if (b != ZModem.ZDLE) {
            return b & 0xff;
        }
        return decodeEscapeTail(in.read());
    }

    static int decodeEscapeTail(int c) {
        if (c < 0) {
            return -1;
        }
        if (c == ZModem.ZRUB0) {
            return 0x7f;
        }
        if (c == ZModem.ZRUB1) {
            return 0xff;
        }
        // 规范：ZDLE 后跟「bit6 置 1、bit5 清 0」的字节时反转 bit6（bit7 是奇偶位，两种变体都要接受）
        if ((c & 0x60) == 0x40) {
            return c ^ 0x40;
        }
        return -1;
    }

    // ---- 数据子包读取（接收侧） ----

    /**
     * 读取一个数据子包，返回数据和结束符（ZCRCE/ZCRCG/ZCRCQ/ZCRCW）。
     * valid=false 表示 CRC 不匹配。接收侧应忽略流中的原始 XON/XOFF（规范 §7.2）。
     */
    public static Subpacket readSubpacket(InputStream in, boolean useCrc32)
            throws IOException, ZModemCancelledException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(ZModem.MAX_SUBPACKET);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("stream closed in data subpacket");
            }
            if (b == ZModem.ZDLE) {
                // 连续 ZDLE 计数：5 个即取消序列
                int run = 1;
                int c;
                while ((c = in.read()) == ZModem.ZDLE) {
                    run++;
                    if (run >= 5) {
                        throw new ZModemCancelledException();
                    }
                }
                if (c < 0) {
                    throw new EOFException("stream closed after ZDLE run");
                }
                if (c == ZModem.ZCRCE || c == ZModem.ZCRCG
                        || c == ZModem.ZCRCQ || c == ZModem.ZCRCW) {
                    return finishSubpacket(in, buf, c, useCrc32);
                }
                int v = decodeEscapeTail(c);
                if (v < 0) {
                    throw new IOException("invalid ZDLE escape in data subpacket");
                }
                buf.write(v);
                continue;
            }
            // 规范：接收方忽略数据流中的 XON/XOFF 及其高位变体
            if (b == 0x11 || b == 0x13 || b == 0x91 || b == 0x93) {
                continue;
            }
            if (b == 0x10) {
                continue;   // 同上
            }
            buf.write(b);
            if (buf.size() > ZModem.MAX_SUBPACKET * 2 + 64) {
                throw new IOException("data subpacket exceeds protocol limit");
            }
        }
    }

    private static Subpacket finishSubpacket(InputStream in, java.io.ByteArrayOutputStream buf,
                                             int frameEnd, boolean useCrc32) throws IOException {
        byte[] data = buf.toByteArray();
        if (useCrc32) {
            CRC32 crc = new CRC32();
            crc.update(data);
            crc.update(frameEnd);
            long expected = crc.getValue();
            long got = (((long) readEscapedLenient(in) & 0xffL) << 24)
                    | (((long) readEscapedLenient(in) & 0xffL) << 16)
                    | (((long) readEscapedLenient(in) & 0xffL) << 8)
                    | (readEscapedLenient(in) & 0xffL);
            return new Subpacket(data, frameEnd, got == expected);
        }
        int crc = 0;
        for (byte datum : data) {
            crc = crc16Update(crc, datum & 0xff);
        }
        crc = crc16Update(crc, frameEnd);
        int hi = readEscapedLenient(in);
        int lo = readEscapedLenient(in);
        if (hi < 0 || lo < 0) {
            throw new EOFException("stream closed in subpacket CRC");
        }
        int got = ((hi & 0xff) << 8) | (lo & 0xff);
        return new Subpacket(data, frameEnd, got == crc);
    }

    /** 数据模式下读取解码字节：原始 XON/XOFF 及高位变体一律忽略（lrzsz zrdata 行为）。 */
    static int readEscapedLenient(InputStream in) throws IOException {
        while (true) {
            int b = in.read();
            if (b < 0) {
                return -1;
            }
            if (b == 0x11 || b == 0x13 || b == 0x91 || b == 0x93) {
                continue;
            }
            if (b != ZModem.ZDLE) {
                return b & 0xff;
            }
            return decodeEscapeTail(in.read());
        }
    }
}
