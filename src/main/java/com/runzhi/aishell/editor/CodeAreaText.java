/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import org.fxmisc.richtext.CodeArea;

/**
 * RichTextFX CodeArea 的 {@link EditorText} 实现。
 */
final class CodeAreaText implements EditorText {

    private final CodeArea area = new CodeArea();

    @Override public String getText() { return area.getText(); }
    @Override public void setText(String text) { area.replaceText(text == null ? "" : text); }
    @Override public void appendText(String text) { area.appendText(text == null ? "" : text); }
    @Override public String getSelectedText() { return area.getSelectedText(); }
    @Override public void selectRange(int anchor, int caret) { area.selectRange(anchor, caret); }
    @Override public void replaceSelection(String text) { area.replaceSelection(text); }
    @Override public int getCaretPosition() { return area.getCaretPosition(); }
    @Override public boolean isEditable() { return area.isEditable(); }
    @Override public void setEditable(boolean editable) { area.setEditable(editable); }
    @Override public void requestFocus() { area.requestFocus(); }
    @Override public javafx.scene.Node node() { return area; }
    @Override public void addTextChangeListener(Runnable listener) {
        // 不用 plainTextChanges()：其返回类型 org.reactfx.EventStream 属于 unnamed module，
        // 命名模块 com.runzhi.aishell 无权访问（运行期 IllegalAccessError）；textProperty 是纯 JavaFX 类型。
        area.textProperty().addListener((obs, oldText, newText) -> listener.run());
    }

    CodeArea area() { return area; }
}
