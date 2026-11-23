/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import javafx.scene.Node;
import javafx.scene.control.TextArea;

import java.util.List;

/**
 * 编辑器正文组件的薄抽象（方案 §10 风险隔离）。
 *
 * <p>RichTextFX 0.11 的 CodeArea 不是 JavaFX TextInputControl 子类，
 * 本接口把编辑器真正需要的方法（读写文本、选中、替换、光标、可编辑、监听）抽象出来，
 * TextArea 与 CodeArea 各有一份实现，编辑逻辑无感知。
 */
public interface EditorText {

    String getText();

    void setText(String text);

    void appendText(String text);

    String getSelectedText();

    void selectRange(int anchor, int caret);

    void replaceSelection(String text);

    int getCaretPosition();

    boolean isEditable();

    void setEditable(boolean editable);

    void requestFocus();

    /** 场景图节点（Tab 内容与样式类挂载用）。 */
    Node node();

    default List<String> styleClass() {
        return node().getStyleClass();
    }

    /** 正文变化监听（回调在 FX 线程触发）。 */
    void addTextChangeListener(Runnable listener);

    /** TextArea 实现。 */
    final class TextAreaText implements EditorText {
        private final TextArea area = new TextArea();

        @Override public String getText() { return area.getText(); }
        @Override public void setText(String text) { area.setText(text == null ? "" : text); }
        @Override public void appendText(String text) { area.appendText(text == null ? "" : text); }
        @Override public String getSelectedText() { return area.getSelectedText(); }
        @Override public void selectRange(int anchor, int caret) { area.selectRange(anchor, caret); }
        @Override public void replaceSelection(String text) { area.replaceSelection(text); }
        @Override public int getCaretPosition() { return area.getCaretPosition(); }
        @Override public boolean isEditable() { return area.isEditable(); }
        @Override public void setEditable(boolean editable) { area.setEditable(editable); }
        @Override public void requestFocus() { area.requestFocus(); }
        @Override public Node node() { return area; }
        @Override public void addTextChangeListener(Runnable listener) {
            area.textProperty().addListener((obs, oldText, newText) -> listener.run());
        }

        TextArea area() { return area; }
    }
}
