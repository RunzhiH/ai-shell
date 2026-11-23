/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.monitor;

import lombok.Value;

/**
 * 远程命令执行结果。
 *
 * <p>区分超时、失败与成功：单项命令失败只影响对应指标的状态，不使整轮采样失败。
 */
@Value
public class CommandResult {

    boolean success;
    boolean timedOut;
    String output;

    public static CommandResult success(String output) {
        return new CommandResult(true, false, output == null ? "" : output);
    }

    public static CommandResult timeout() {
        return new CommandResult(false, true, "");
    }

    public static CommandResult failure() {
        return new CommandResult(false, false, "");
    }

    public boolean hasOutput() {
        return success && output != null && !output.isEmpty();
    }
}
