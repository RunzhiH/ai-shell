/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个标签的 UI 绑定（方案 §4.1）：Tab + 正文组件 + 文档状态。
 *
 * <p>正文组件由 {@link EditorTextFactory} 创建：有显示器的环境为 RichTextFX CodeArea
 * （行号 + 语法高亮），无显示器环境退回 TextArea；两者都是 TextInputControl，编辑逻辑无感知。
 * 标签标题 = 文件名 + 脏标记，tooltip 显示完整路径。
 */
public final class EditorTab {

    private final Tab tab;
    private final EditorText editor;
    private volatile EditorDocument document;
    private final EditorService service;
    /** 每个文档同一时刻只允许一个保存任务（方案 §5.5）。 */
    private final AtomicBoolean saving = new AtomicBoolean();

    EditorTab(Tab tab, EditorText editor, EditorDocument document, EditorService service) {
        this.tab = tab;
        this.editor = editor;
        this.document = document;
        this.service = service;
    }

    public Tab tab() { return tab; }
    public EditorText editor() { return editor; }
    public EditorDocument document() { return document; }
    public EditorService service() { return service; }

    /** 当前文本按文档编码/EOL/BOM 重新编码为待写字节。 */
    public byte[] currentBytes() {
        return TextCodec.encode(editor.getText(), document.encoding(), document.bom(), document.eol());
    }

    /** 标签标题：文件名 + 脏标记。 */
    public void refreshTitle() {
        String title = document.displayName() + (document.dirty() ? " ●" : "");
        tab.setText(title);
        if (tab.getTooltip() != null) tab.getTooltip().setText(document.displayPath());
    }

    /** 打开/重载后用新内容与状态刷新 UI。 */
    public void renderContent(String text) {
        editor.setText(text);
        editor.setEditable(!document.readOnly());
        refreshTitle();
    }

    public void markClean() {
        document.setDirty(false);
        refreshTitle();
    }

    public void markDirty() {
        if (!document.dirty()) {
            document.setDirty(true);
            refreshTitle();
        }
    }

    /** 尝试占位保存：同一文档并发保存只允许一个。 */
    public boolean startSave() {
        return saving.compareAndSet(false, true);
    }

    public void finishSave() {
        saving.set(false);
    }

    public boolean isSaving() {
        return saving.get();
    }

    /** 重新加载 / 切换编码后用新文档与内容替换绑定（保留同一个 Tab 与正文组件）。 */
    public void replace(EditorDocument replacement, String text) {
        replacement.setDirty(document.dirty());
        this.document = replacement;
        renderContent(text);
    }
}
