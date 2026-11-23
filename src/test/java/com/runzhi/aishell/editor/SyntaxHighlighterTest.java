package com.runzhi.aishell.editor;

import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轻量语法高亮单测：规则优先级（注释遮蔽关键字）、覆盖整段文本、无语言时 plain。
 */
class SyntaxHighlighterTest {

    @Test
    void unknownLanguageIsPlain() {
        assertTrue(SyntaxHighlighter.forLanguage(null).isPlain());
        assertTrue(SyntaxHighlighter.forLanguage("Text").isPlain());
        assertFalse(SyntaxHighlighter.forLanguage("Java").isPlain());
    }

    @Test
    void commentsShadowKeywordsInJava() {
        SyntaxHighlighter java = SyntaxHighlighter.forLanguage("Java");
        StyleSpans<Collection<String>> spans = java.compute("// public static void\nint x = 1;");
        String result = styledOf(spans);
        // 行注释里的 public 不应被关键字规则命中
        assertFalse(result.contains("syntax-keyword@6"));
        assertTrue(result.contains("syntax-comment"));
        assertTrue(result.contains("syntax-keyword@3"));
        assertTrue(result.contains("syntax-number@1"));
    }

    @Test
    void stringsAreDetectedInPython() {
        SyntaxHighlighter python = SyntaxHighlighter.forLanguage("Python");
        StyleSpans<Collection<String>> spans = python.compute("print('hello')");
        String result = styledOf(spans);
        assertTrue(result.contains("syntax-string@7"));
    }

    @Test
    void hashCommentIsNotConfusedWithSharpColorInCss() {
        SyntaxHighlighter css = SyntaxHighlighter.forLanguage("CSS");
        StyleSpans<Collection<String>> spans = css.compute("a { color: #ff0000; }");
        String result = styledOf(spans);
        // #ff0000 应走 number 规则而非注释
        assertFalse(result.contains("syntax-comment"));
        assertTrue(result.contains("syntax-number@7"));
    }

    @Test
    void xmlMatchesTagsAndComments() {
        SyntaxHighlighter xml = SyntaxHighlighter.forLanguage("XML");
        StyleSpans<Collection<String>> spans = xml.compute("<!-- hi --><root attr=\"v\"/>");
        String result = styledOf(spans);
        assertTrue(result.contains("syntax-comment@11"));
        assertTrue(result.contains("syntax-tag@5"));
        assertTrue(result.contains("syntax-string@3"));
    }

    @Test
    void plainHighlighterCoversWholeTextWithoutStyles() {
        StyleSpans<Collection<String>> spans = SyntaxHighlighter.forLanguage("Text").compute("anything at all");
        assertEquals(15, spans.length());
        spans.forEach(span -> assertTrue(span.getStyle().isEmpty()));
    }

    @Test
    void computeProducesSpansEqualToTextLength() {
        for (String language : new String[]{"Java", "Shell", "SQL", "JSON", "YAML"}) {
            SyntaxHighlighter highlighter = SyntaxHighlighter.forLanguage(language);
            StyleSpans<Collection<String>> spans = highlighter.compute("select * from t where id = 1 # note");
            assertEquals(35, spans.length(), language + " spans must cover the whole text");
        }
    }

    @Test
    void emptyTextIsSafe() {
        SyntaxHighlighter java = SyntaxHighlighter.forLanguage("Java");
        StyleSpans<Collection<String>> spans = java.compute("");
        assertEquals(0, spans.length());
    }

    /** 把样式区间展平为 "style@length;..." 便于断言。 */
    private static String styledOf(StyleSpans<Collection<String>> spans) {
        StringBuilder builder = new StringBuilder();
        spans.forEach(span -> {
            if (!span.getStyle().isEmpty()) {
                builder.append(span.getStyle().iterator().next()).append('@').append(span.getLength()).append(';');
            }
        });
        return builder.toString();
    }
}
