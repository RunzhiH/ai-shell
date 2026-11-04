/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ZModemCodecTest {

    @Test
    void crc16MatchesKnownVector() {
        byte[] data = "123456789".getBytes();
        assertEquals(0x31C3, ZModemCodec.crc16(data, 0, data.length),
                "XMODEM CRC16 of \"123456789\" must be 0x31C3");
    }

    @Test
    void crc32MatchesKnownVector() {
        CRC32 crc = new CRC32();
        crc.update("123456789".getBytes());
        assertEquals(0xCBF43926L, crc.getValue());
    }

    @Test
    void escapeRoundtripAllBytes() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int i = 0; i < 256; i++) {
            ZModemCodec.writeEscaped(raw, i);
        }
        byte[] wire = raw.toByteArray();
        // 线上不应出现未被转义的 ZDLE
        for (int i = 0; i < wire.length; i++) {
            if (wire[i] == ZModem.ZDLE) {
                int next = wire[i + 1] & 0xff;
                assertNotEquals(ZModem.ZDLE, next, "raw ZDLE must not appear unescaped twice in a row");
            }
        }
        // 逐字节解码回来
        ByteArrayInputStream in = new ByteArrayInputStream(wire);
        for (int i = 0; i < 256; i++) {
            assertEquals(i, ZModemCodec.readEscaped(in), "byte " + i + " roundtrip");
        }
        assertEquals(-1, ZModemCodec.readEscaped(in));
    }

    @Test
    void hexHeaderRoundtrip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZModemCodec.writeHexHeader(out, ZModem.ZRINIT, ZModemHeader.flagsData(0x27, 0));
        ZModemHeader h = ZModemCodec.readHeader(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(ZModem.ZRINIT, h.type());
        assertEquals(0x27, h.flagsLow());
    }

    @Test
    void binaryHeaderRoundtripWithTrickyBytes() throws IOException {
        for (boolean crc32 : new boolean[] {false, true}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int[] data = ZModemHeader.positionData(0x11223318L);
            ZModemCodec.writeBinaryHeader(out, ZModem.ZDATA, data, crc32);
            ZModemHeader h = ZModemCodec.readHeader(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(ZModem.ZDATA, h.type());
            assertEquals(0x11223318L, h.position());
        }
    }

    @Test
    void subpacketRoundtripAllByteValues() throws IOException {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (boolean crc32 : new boolean[] {false, true}) {
            out.reset();
            ZModemCodec.writeSubpacket(out, data, 0, data.length, ZModem.ZCRCG, crc32);
            ZModemCodec.Subpacket p = ZModemCodec.readSubpacket(
                    new ByteArrayInputStream(out.toByteArray()), crc32);
            assertTrue(p.valid(), "crc32=" + crc32);
            assertEquals(ZModem.ZCRCG, p.frameEnd());
            assertArrayEquals(data, p.data());
        }
    }

    @Test
    void subpacketWithZdleRunDataRoundtrip() throws IOException {
        // 全是 0x18 的数据必须完全转义后原样还原
        byte[] data = new byte[64];
        java.util.Arrays.fill(data, (byte) 0x18);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZModemCodec.writeSubpacket(out, data, 0, data.length, ZModem.ZCRCW, false);
        ZModemCodec.Subpacket p = ZModemCodec.readSubpacket(
                new ByteArrayInputStream(out.toByteArray()), false);
        assertTrue(p.valid());
        assertArrayEquals(data, p.data());
    }

    @Test
    void corruptedSubpacketIsInvalid() throws IOException {
        byte[] data = "hello zmodem".getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZModemCodec.writeSubpacket(out, data, 0, data.length, ZModem.ZCRCG, false);
        byte[] wire = out.toByteArray();
        wire[wire.length - 1] ^= 0x01;   // 破坏 CRC 最后一字节
        ZModemCodec.Subpacket p = ZModemCodec.readSubpacket(
                new ByteArrayInputStream(wire), false);
        assertFalse(p.valid());
    }

    @Test
    void fiveCancelsThrowCancelled() {
        byte[] wire = new byte[] {0x18, 0x18, 0x18, 0x18, 0x18};
        assertThrows(ZModemCancelledException.class,
                () -> ZModemCodec.readHeader(new ByteArrayInputStream(wire)));
    }

    @Test
    void cancelSequenceDetectedInSubpacket() {
        byte[] wire = new byte[] {'a', 'b', 0x18, 0x18, 0x18, 0x18, 0x18};
        assertThrows(ZModemCancelledException.class,
                () -> ZModemCodec.readSubpacket(new ByteArrayInputStream(wire), false));
    }

    @Test
    void xonXoffIgnoredInData() throws IOException {
        byte[] wire = new byte[] {'a', 0x11, 'b', 0x13, 0x18, 'k', 0x00, 0x00};   // CRC 0x00 故意不算
        // 只验证 XON/XOFF 被跳过：用合法子包前缀
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZModemCodec.writeSubpacket(out, new byte[] {'a', 'b'}, 0, 2, ZModem.ZCRCE, false);
        byte[] base = out.toByteArray();
        // 在数据与 CRC 间插入 XON/XOFF（合法：接收方应忽略）
        byte[] wire2 = new byte[base.length + 2];
        int cut = base.length - 2;   // 前面是数据+结束符
        System.arraycopy(base, 0, wire2, 0, cut);
        wire2[cut] = 0x11;
        wire2[cut + 1] = 0x13;
        System.arraycopy(base, cut, wire2, cut + 2, 2);
        ZModemCodec.Subpacket p = ZModemCodec.readSubpacket(new ByteArrayInputStream(wire2), false);
        // CRC 计算覆盖数据与结束符，XON/XOFF 被忽略后应校验通过
        assertTrue(p.valid());
        assertArrayEquals(new byte[] {'a', 'b'}, p.data());
    }
}
