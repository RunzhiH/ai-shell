/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

/**
 * ZMODEM 协议常量（帧类型、帧指示、能力位、数据子包结束符等）。
 * 数值与 ZMODEM 规范（Forsberg）及 lrzsz 的 zmodem.h 保持一致。
 */
public final class ZModem {

    private ZModem() {
    }

    /** 帧前导与转义 */
    public static final int ZPAD = '*';          // 0x2A
    public static final int ZDLE = 0x18;         // 数据链路转义
    public static final int ZBIN = 'A';          // 二进制头（16 位 CRC）
    public static final int ZHEX = 'B';          // 十六进制头
    public static final int ZBIN32 = 'C';        // 二进制头（32 位 CRC）

    /** 帧类型 */
    public static final int ZRQINIT = 0;         // 发送方请求接收方初始化
    public static final int ZRINIT = 1;          // 接收方能力声明
    public static final int ZSINIT = 2;          // 发送方初始化（可选，含 Attn 串）
    public static final int ZACK = 3;            // 确认
    public static final int ZFILE = 4;           // 文件信息帧
    public static final int ZSKIP = 5;           // 跳过该文件
    public static final int ZNAK = 6;            // 上一帧损坏
    public static final int ZABORT = 7;          // 中止批量传输
    public static final int ZFIN = 8;            // 结束会话
    public static final int ZRPOS = 9;           // 从指定偏移重传
    public static final int ZDATA = 10;          // 数据帧
    public static final int ZEOF = 11;           // 文件结束
    public static final int ZFERR = 12;          // 读写错误
    public static final int ZCRC = 13;           // 请求/返回文件 CRC
    public static final int ZCHALLENGE = 14;     // 接收方挑战
    public static final int ZCOMPL = 15;         // 命令完成
    public static final int ZCAN = 16;           // 对端用 CAN*5 取消

    /** 数据子包结束符（跟在 ZDLE 之后） */
    public static final int ZCRCE = 'h';         // CRC 后帧结束，后续是新头
    public static final int ZCRCG = 'i';         // CRC 后继续（流式）
    public static final int ZCRCQ = 'j';         // CRC 后继续，期望 ZACK
    public static final int ZCRCW = 'k';         // CRC 后结束，期望 ZACK（同步点）
    public static final int ZRUB0 = 'l';         // 解码为 0x7F
    public static final int ZRUB1 = 'm';         // 解码为 0xFF

    /** ZRINIT 能力位（ZF0/ZF1） */
    public static final int CANFDX = 0x01;       // 全双工
    public static final int CANOVIO = 0x02;      // 收数据可与磁盘 IO 重叠
    public static final int CANBRK = 0x04;       // 可发 break
    public static final int CANFC32 = 0x20;      // 支持 32 位 CRC
    public static final int ESCCTL = 0x40;       // 期望控制字符被转义
    public static final int ESC8 = 0x80;         // 期望第 8 位被转义

    /** ZFILE 的 ZF0 转换选项 */
    public static final int ZCBIN = 1;           // 二进制传输，禁止转换
    public static final int ZCNL = 2;            // 换行转换
    public static final int ZCRESUM = 3;         // 续传

    /** 取消序列：8 个 CAN + 10 个退格 */
    public static final int CANCEL_RETRY = 8;
    public static final int CANCEL_BACKSPACE = 10;

    /** 读写缓冲与子包上限 */
    public static final int MAX_SUBPACKET = 1024;
    public static final int READ_BUFFER = 8192;

    /** 帧类型名称（日志用） */
    public static String frameName(int type) {
        return switch (type) {
            case ZRQINIT -> "ZRQINIT";
            case ZRINIT -> "ZRINIT";
            case ZSINIT -> "ZSINIT";
            case ZACK -> "ZACK";
            case ZFILE -> "ZFILE";
            case ZSKIP -> "ZSKIP";
            case ZNAK -> "ZNAK";
            case ZABORT -> "ZABORT";
            case ZFIN -> "ZFIN";
            case ZRPOS -> "ZRPOS";
            case ZDATA -> "ZDATA";
            case ZEOF -> "ZEOF";
            case ZFERR -> "ZFERR";
            case ZCRC -> "ZCRC";
            case ZCHALLENGE -> "ZCHALLENGE";
            case ZCOMPL -> "ZCOMPL";
            case ZCAN -> "ZCAN";
            default -> "TYPE-" + type;
        };
    }
}
