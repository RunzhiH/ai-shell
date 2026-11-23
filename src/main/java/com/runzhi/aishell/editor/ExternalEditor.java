/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部编辑器：名称、命令模板（含 {file} 占位）、来源（方案 §4.1）。
 *
 * <p>命令以 {@code List<String>} 交给 {@link ProcessBuilder}，不经过任何 shell；
 * 自定义命令只做 {file} 占位替换，不做 shell 展开（方案 §5.6）。
 */
public record ExternalEditor(String name, List<String> commandTemplate, Source source) {

    public enum Source {
        CUSTOM, VSCODE, NOTEPAD_PLUS_PLUS, SUBLIME, VIM, JETBRAINS, WINDOWS_NOTEPAD, TEXT_EDIT, GEDIT, KATE,
        SYSTEM_DEFAULT
    }

    /**
     * 把命令模板中的 {file} 替换为实际路径，返回可直接启动的参数列表。
     * 非 SYSTEM_DEFAULT 编辑器模板必须包含 {file}。
     */
    public List<String> buildCommand(String filePath) {
        List<String> command = new ArrayList<>(commandTemplate.size());
        for (String part : commandTemplate) {
            command.add(part.replace("{file}", filePath));
        }
        return command;
    }

    public boolean isSystemDefault() {
        return source == Source.SYSTEM_DEFAULT;
    }
}
