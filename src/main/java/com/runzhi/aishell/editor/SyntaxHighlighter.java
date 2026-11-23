/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量语法高亮（方案 §8 P3：RichTextFX + 轻量 lexer）。
 *
 * <p>规则是正则级的：注释 / 字符串 / 数字 / 关键字 / 标签按优先级单轮匹配，
 * 生成 {@link StyleSpans}（样式类见 style.css / theme-light.css 的 .syntax-*）。
 * 不追求编译器级准确，够远端配置文件快速阅读即可；大文件由编辑器上限（默认 2MB）兜底。
 */
public final class SyntaxHighlighter {

    private static final SyntaxHighlighter PLAIN = new SyntaxHighlighter(null, Set.of());
    private static final Set<String> TEXT_GROUPS = Set.of("comment", "string", "number", "keyword");
    private static final Set<String> TAG_GROUPS = Set.of("comment", "string", "tag");
    private static final Set<String> CSS_GROUPS = Set.of("comment", "string", "number");
    private static final Set<String> MARKDOWN_GROUPS = Set.of("string", "number");

    private static final String STRING = "(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')";
    private static final String NUMBER = "(?<number>\\b\\d+(?:\\.\\d+)?\\b)";
    private static final String C_LINE_COMMENT = "//[^\n]*";
    private static final String C_BLOCK_COMMENT = "/\\*[\\s\\S]*?\\*/";
    private static final String HASH_COMMENT = "#[^\n]*";
    private static final String SQL_LINE_COMMENT = "--[^\n]*";
    private static final String XML_COMMENT = "<!--[\\s\\S]*?-->";

    private static final Map<String, List<String>> KEYWORDS = Map.ofEntries(
            Map.entry("Java", List.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                    "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                    "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
                    "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                    "transient", "try", "void", "volatile", "while", "var", "record", "yield", "true", "false",
                    "null")),
            Map.entry("JavaScript", List.of("var", "let", "const", "function", "return", "if", "else", "for",
                    "while", "do", "switch", "case", "break", "continue", "new", "this", "typeof", "instanceof",
                    "in", "of", "try", "catch", "finally", "throw", "class", "extends", "super", "import", "export",
                    "default", "yield", "async", "await", "true", "false", "null", "undefined")),
            Map.entry("TypeScript", List.of("var", "let", "const", "function", "return", "if", "else", "for",
                    "while", "switch", "case", "break", "continue", "new", "this", "typeof", "instanceof", "try",
                    "catch", "finally", "throw", "class", "extends", "interface", "implements", "type", "enum",
                    "import", "export", "default", "async", "await", "public", "private", "protected", "readonly",
                    "number", "string", "boolean", "void", "any", "null", "undefined", "true", "false")),
            Map.entry("C", List.of("int", "char", "double", "float", "void", "long", "short", "unsigned", "signed",
                    "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "struct",
                    "union", "enum", "typedef", "static", "const", "extern", "volatile", "register", "auto", "goto",
                    "sizeof", "true", "false", "NULL")),
            Map.entry("C++", List.of("int", "char", "double", "float", "void", "long", "short", "auto", "if",
                    "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "struct", "class",
                    "union", "enum", "typedef", "namespace", "using", "template", "typename", "static", "const",
                    "extern", "volatile", "virtual", "override", "public", "private", "protected", "new", "delete",
                    "this", "true", "false", "nullptr")),
            Map.entry("Go", List.of("func", "var", "const", "type", "struct", "interface", "map", "chan", "package",
                    "import", "if", "else", "for", "range", "switch", "case", "default", "break", "continue",
                    "return", "defer", "go", "select", "nil", "true", "false", "make", "len", "append")),
            Map.entry("Rust", List.of("fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
                    "match", "if", "else", "for", "while", "loop", "return", "use", "mod", "pub", "as", "ref",
                    "move", "self", "Self", "true", "false", "Option", "Result", "Some", "None", "Ok", "Err")),
            Map.entry("Python", List.of("def", "class", "return", "if", "elif", "else", "for", "while", "break",
                    "continue", "import", "from", "as", "pass", "try", "except", "finally", "raise", "with",
                    "lambda", "yield", "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or",
                    "None", "True", "False", "self")),
            Map.entry("Shell", List.of("if", "then", "fi", "else", "elif", "case", "esac", "for", "while", "do",
                    "done", "in", "function", "return", "export", "local", "readonly", "unset", "shift", "exit",
                    "echo", "cd", "set", "source")),
            Map.entry("SQL", List.of("SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
                    "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT", "INNER",
                    "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "COUNT", "SUM",
                    "AVG", "MAX", "MIN", "AS", "AND", "OR", "NOT", "NULL", "PRIMARY", "KEY", "FOREIGN",
                    "REFERENCES", "DEFAULT", "CASCADE", "BEGIN", "COMMIT", "ROLLBACK")),
            Map.entry("JSON", List.of("true", "false", "null")),
            Map.entry("YAML", List.of("true", "false", "null", "yes", "no"))
    );

    private final Pattern pattern;
    private final Set<String> groups;

    private SyntaxHighlighter(Pattern pattern, Set<String> groups) {
        this.pattern = pattern;
        this.groups = groups;
    }

    public static SyntaxHighlighter forLanguage(String language) {
        if (language == null) return PLAIN;
        return switch (language) {
            case "Java", "C++", "C", "JavaScript", "TypeScript", "Go", "Rust" ->
                    new SyntaxHighlighter(Pattern.compile(
                            "(?<comment>" + C_LINE_COMMENT + "|" + C_BLOCK_COMMENT + ")"
                                    + "|" + STRING + "|" + NUMBER
                                    + "|(?<keyword>\\b(?:" + alternation(KEYWORDS.get(language)) + ")\\b)"),
                            TEXT_GROUPS);
            case "Python", "Shell", "YAML", "Properties", "Log", "Config" ->
                    new SyntaxHighlighter(Pattern.compile(
                            "(?<comment>" + HASH_COMMENT + ")"
                                    + "|" + STRING + "|" + NUMBER
                                    + "|(?<keyword>\\b(?:" + alternation(KEYWORDS.get(language)) + ")\\b)"),
                            TEXT_GROUPS);
            case "SQL" ->
                    new SyntaxHighlighter(Pattern.compile(
                            "(?<comment>" + SQL_LINE_COMMENT + "|" + C_BLOCK_COMMENT + ")"
                                    + "|" + STRING + "|" + NUMBER
                                    + "|(?<keyword>\\b(?:" + alternation(KEYWORDS.get("SQL")) + ")\\b)"),
                            TEXT_GROUPS);
            case "XML", "HTML", "FXML" ->
                    new SyntaxHighlighter(Pattern.compile(
                            "(?<comment>" + XML_COMMENT + ")"
                                    + "|(?<string>\"[^\"]*\"|'[^']*')"
                                    + "|(?<tag></?[A-Za-z][A-Za-z0-9:_.-]*|/?>)"),
                            TAG_GROUPS);
            case "CSS" ->
                    new SyntaxHighlighter(Pattern.compile(
                            "(?<comment>" + C_BLOCK_COMMENT + ")"
                                    + "|" + STRING
                                    + "|(?<number>#[0-9A-Fa-f]{3,8}\\b|\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|pt)?\\b)"),
                            CSS_GROUPS);
            case "Markdown" ->
                    new SyntaxHighlighter(Pattern.compile(
                            "(?<string>\\*\\*[^*]+\\*\\*)"
                                    + "|(?<number>^#{1,6}[^\\n]*)"),
                            MARKDOWN_GROUPS);
            default -> PLAIN;
        };
    }

    public boolean isPlain() {
        return pattern == null;
    }

    /** 计算整段文本的样式区间；空区间用空集合占位，JavaFX CSS 自动继承正文颜色。 */
    public StyleSpans<Collection<String>> compute(String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty() || pattern == null) {
            if (text != null) builder.add(Collections.emptyList(), text.length());
            return builder.create();
        }
        Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClassOf(matcher)), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    private String styleClassOf(Matcher matcher) {
        if (groups.contains("comment") && matcher.start("comment") >= 0) return "syntax-comment";
        if (groups.contains("string") && matcher.start("string") >= 0) return "syntax-string";
        if (groups.contains("keyword") && matcher.start("keyword") >= 0) return "syntax-keyword";
        if (groups.contains("number") && matcher.start("number") >= 0) return "syntax-number";
        if (groups.contains("tag") && matcher.start("tag") >= 0) return "syntax-tag";
        return "syntax-text";
    }

    private static String alternation(List<String> words) {
        if (words == null || words.isEmpty()) return "undefined";
        return String.join("|", words);
    }
}
