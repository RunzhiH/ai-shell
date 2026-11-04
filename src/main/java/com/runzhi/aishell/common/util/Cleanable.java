/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

/**
 * 视图销毁时可清理资源的控制器实现此接口。
 * 由 {@link com.runzhi.aishell.common.manager.NavigationManager#openInNewWindow} 在独立窗口隐藏后调用，清除导航缓存时也会调用。
 * 普通页面切换、收起 Agent 面板不会触发清理；实现必须支持重复调用。
 */
public interface Cleanable {
    /**
     * 窗口生命周期结束或缓存销毁时在 FX 线程调用，用于取消后台任务、释放资源。
     * 实现应快速返回，不得阻塞 FX 线程。
     */
    void onWindowClosing();
}
