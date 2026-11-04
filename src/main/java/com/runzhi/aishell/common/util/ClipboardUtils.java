/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.common.util;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * 系统剪贴板写入的统一入口。
 *
 * <p>JavaFX Clipboard 在部分 Windows 会话/窗口状态下可能没有真正写入，且不会抛异常；
 * 这里在 JavaFX 写入后用 {@link Clipboard#getString()} 校验，失败时回退到 AWT 剪贴板。</p>
 */
public final class ClipboardUtils {
    private ClipboardUtils() { }

    /** @return true 表示至少一种剪贴板实现接受了文本。 */
    public static boolean copyText(String text) {
        if (text == null || text.isEmpty()) return false;
        if (copyWithJavaFx(text)) return true;
        return copyWithAwt(text);
    }

    private static boolean copyWithJavaFx(String text) {
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard clipboard = Clipboard.getSystemClipboard();
            clipboard.setContent(content);
            return text.equals(clipboard.getString());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean copyWithAwt(String text) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
