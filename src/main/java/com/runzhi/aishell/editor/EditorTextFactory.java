/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import javafx.animation.PauseTransition;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.awt.GraphicsEnvironment;

/**
 * 编辑器正文组件工厂（方案 §10 风险隔离：P1 用 TextArea，P3 升级到 RichTextFX CodeArea）。
 *
 * <p>CodeArea 提供行号与语法高亮，但在无显示器环境（CI）构造会抛
 * ArrayIndexOutOfBoundsException（见 AllFxmlLoadSmokeTest 对 terminal-tab.fxml 的已知限制）。
 * 因此只在 {@link GraphicsEnvironment#isHeadless()} 为 false 时使用 CodeArea，
 * 并对构造异常兜底回退 TextArea，保证同一套编辑器逻辑在两种环境下都可用。
 */
@Slf4j
public final class EditorTextFactory {

    private static final double HIGHLIGHT_DEBOUNCE_MS = 250;
    private static final String MONOSPACE_CSS = "-fx-font-family: monospace; -fx-font-size: 13px;";

    private EditorTextFactory() {
    }

    /** 是否会使用 RichTextFX CodeArea（行号 + 语法高亮）。 */
    public static boolean codeAreaSupported() {
        return !GraphicsEnvironment.isHeadless();
    }

    /** 创建编辑器正文组件：有显示器的环境用 CodeArea，否则退回 TextArea。 */
    public static EditorText create(EditorDocument document) {
        if (codeAreaSupported()) {
            try {
                return createCodeArea(document);
            } catch (Throwable failure) {
                log.warn("CodeArea creation failed, falling back to TextArea: {}", failure.toString());
            }
        }
        EditorText.TextAreaText text = new EditorText.TextAreaText();
        text.area().setWrapText(false);
        text.area().setStyle(MONOSPACE_CSS);
        return text;
    }

    private static EditorText createCodeArea(EditorDocument document) {
        CodeAreaText text = new CodeAreaText();
        CodeArea area = text.area();
        area.setWrapText(false);
        area.setStyle(MONOSPACE_CSS);
        // 行号
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        attachHighlighting(area, document);
        return text;
    }

    /** 挂载防抖语法高亮：文本变化 250ms 后重算整段样式，初始内容立即着色一次。 */
    private static void attachHighlighting(CodeArea area, EditorDocument document) {
        SyntaxHighlighter highlighter = SyntaxHighlighter.forLanguage(document.language());
        if (highlighter.isPlain()) return;
        PauseTransition pause = new PauseTransition(Duration.millis(HIGHLIGHT_DEBOUNCE_MS));
        pause.setOnFinished(event -> apply(area, highlighter));
        // 同理避免 reactfx 类型：文本变化经 textProperty 触发防抖重算
        area.textProperty().addListener((obs, oldText, newText) -> pause.playFromStart());
        apply(area, highlighter);
    }

    private static void apply(CodeArea area, SyntaxHighlighter highlighter) {
        try {
            area.setStyleSpans(0, highlighter.compute(area.getText()));
        } catch (Exception failure) {
            log.debug("Syntax highlighting skipped: {}", failure.getMessage());
        }
    }
}
