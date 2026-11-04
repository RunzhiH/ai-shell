/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.controller;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Agent 工作区与终端标签之间的会话级桥接。
 *
 * <p>Agent 的提议、审批与停止操作必须作用于产生任务的会话（按 tab id 定位），
 * 而不是动作发生时恰好选中的标签。桥接只暴露 sessionId 语义，
 * 标签查找细节留在终端面板控制器内。目标会话不存在或已断开时，
 * 填入操作返回 false —— 不得回退到当前标签把旧审批/提议重新路由。
 */
public record AgentWorkspaceBridge(
        /** (sessionId, command)：把命令填入该会话标签的输入框；目标失效返回 false，不执行。 */
        BiFunction<String, String, Boolean> insertCommand,
        /** sessionId：取消该会话上正在运行的 Agent 任务。 */
        Consumer<String> cancelSession) {
    public static AgentWorkspaceBridge empty() {
        return new AgentWorkspaceBridge((sessionId, command) -> false, sessionId -> { });
    }
}
