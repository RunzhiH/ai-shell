/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.terminal.zmodem;

import java.io.IOException;

/** 对端发送了 CAN*5 取消序列（或 5 个以上 CAN）。 */
public class ZModemCancelledException extends IOException {

    public ZModemCancelledException() {
        super("remote cancelled the ZMODEM session");
    }
}
