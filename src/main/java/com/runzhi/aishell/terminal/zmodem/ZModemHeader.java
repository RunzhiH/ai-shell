/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

/**
 * 解析后的 ZMODEM 帧头：frameIndicator 为 {@link ZModem#ZBIN}/{@link ZModem#ZBIN32}/{@link ZModem#ZHEX}，
 * data 为线上的 4 个原始字节（按发送顺序）。
 * <p>
 * 线上字节序（lrzsz 的 zmodem.h）：位置帧 [P0..P3] 小端（P0 为最低位字节）；
 * 选项帧 [F3..F0]，其中 F0（{@link #flagsLow()}）为第一个选项字节、位于线上最后一字节。
 */
public record ZModemHeader(int frameIndicator, int type, int[] data) {

    /** 位置（ZP0..ZP3 小端组装），用于 ZRPOS/ZDATA/ZEOF/ZACK。 */
    public long position() {
        return (data[0] & 0xffL)
                | ((data[1] & 0xffL) << 8)
                | ((data[2] & 0xffL) << 16)
                | ((data[3] & 0xffL) << 24);
    }

    /** ZF0：选项/能力低字节（ZRINIT 能力位、ZFILE 转换选项）。 */
    public int flagsLow() {
        return data[3] & 0xff;
    }

    /** ZF1：选项/能力高字节。 */
    public int flagsHigh() {
        return data[2] & 0xff;
    }

    /** 组装位置帧数据（P0 在前）。 */
    public static int[] positionData(long offset) {
        return new int[] {
                (int) (offset & 0xff),
                (int) ((offset >>> 8) & 0xff),
                (int) ((offset >>> 16) & 0xff),
                (int) ((offset >>> 24) & 0xff)
        };
    }

    /** 组装选项帧数据（低字节选项放在线上最后一字节）。 */
    public static int[] flagsData(int flagsLow, int flagsHigh) {
        return new int[] {0, 0, flagsHigh & 0xff, flagsLow & 0xff};
    }
}
