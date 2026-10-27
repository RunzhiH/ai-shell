/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

/**
 * 视图关闭时可清理资源的控制器实现此接口。
 * 由 {@link com.runzhi.aishell.common.manager.NavigationManager#openInNewWindow} 统一挂载窗口关闭钩子。
 */
public interface Cleanable {
    /**
     * 窗口即将关闭时调用，用于取消后台任务、释放资源。
     * 实现应快速返回，不得阻塞 FX 线程。
     */
    void onWindowClosing();
}
