/*
 * Copyright (C) 2026 runzhi
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.runzhi.aishell.editor;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 文本编解码工具：BOM/编码检测、严格解码、EOL 统计与规范化、二进制判定。
 *
 * <p>检测顺序（方案 §5.1）：
 * <ol>
 *   <li>BOM：UTF-8（EF BB BF）、UTF-16LE（FF FE）、UTF-16BE（FE FF）。</li>
 *   <li>无 BOM：先按 UTF-8 严格解码（REPORT）。</li>
 *   <li>失败则用 editor.fallbackEncoding（默认 GBK）解码。</li>
 *   <li>仍失败：以只读 Latin-1 预览，并提示手动选择编码。</li>
 * </ol>
 *
 * <p>二进制判定（方案 §5.3）：前 8KB 含 NUL，或不可打印字符比例 &gt; 30%。
 */
public final class TextCodec {

    /** 换行符类型：保存时统一为该 EOL，状态栏显示当前值。 */
    public enum Eol {
        LF("\n"), CRLF("\r\n"), CR("\r");

        private final String chars;

        Eol(String chars) { this.chars = chars; }

        public String chars() { return chars; }

        /** 显示名（状态栏）。 */
        public String display() {
            return switch (this) {
                case LF -> "LF";
                case CRLF -> "CRLF";
                case CR -> "CR";
            };
        }
    }

    /** 一次检测结果：只读快照。 */
    public static final class Detection {
        private final String encoding;
        private final boolean bom;
        private final Eol eol;
        private final boolean binary;
        private final boolean previewFallback;
        private final String text;

        public Detection(String encoding, boolean bom, Eol eol,
                         boolean binary, boolean previewFallback, String text) {
            this.encoding = encoding;
            this.bom = bom;
            this.eol = eol;
            this.binary = binary;
            this.previewFallback = previewFallback;
            this.text = text;
        }

        public String encoding() { return encoding; }
        public boolean bom() { return bom; }
        public Eol eol() { return eol; }
        public boolean binary() { return binary; }
        /** Latin-1 只读预览（编码猜测失败），状态栏应提示手动选择编码。 */
        public boolean previewFallback() { return previewFallback; }
        public String text() { return text; }
    }

    private static final int BINARY_SCAN_LIMIT = 8192;

    private TextCodec() {
    }

    /**
     * 检测字节数组的编码/EOL/二进制状态并解码为可编辑文本（EOL 已归一化为 \n）。
     *
     * @param data             原始字节
     * @param fallbackEncoding UTF-8 严格解码失败时的回退编码（如 GBK）；null 则跳过回退
     */
    public static Detection detect(byte[] data, String fallbackEncoding) {
        if (data == null || data.length == 0) {
            return new Detection(StandardCharsets.UTF_8.name(), false, Eol.LF, false, false, "");
        }
        boolean binary = isBinary(data);

        // 1. BOM。带 UTF-16 BOM 的文件必为文本：其高位 0 字节会被 NUL 判定误报为二进制，因此跳过该判定。
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            String text = decodeStrict(StandardCharsets.UTF_8, data, 3);
            return new Detection(StandardCharsets.UTF_8.name(), !binary, dominantEol(text), binary, false, normalizeForDisplay(text));
        }
        if (data.length >= 2) {
            int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                String text = decodeStrict(StandardCharsets.UTF_16LE, data, 2);
                return new Detection(StandardCharsets.UTF_16LE.name(), true, dominantEol(text), false, false, normalizeForDisplay(text));
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                String text = decodeStrict(StandardCharsets.UTF_16BE, data, 2);
                return new Detection(StandardCharsets.UTF_16BE.name(), true, dominantEol(text), false, false, normalizeForDisplay(text));
            }
        }

        // 2. 严格 UTF-8
        String utf8 = decodeStrict(StandardCharsets.UTF_8, data, 0);
        if (utf8 != null) {
            return new Detection(StandardCharsets.UTF_8.name(), false, dominantEol(utf8), binary, false, normalizeForDisplay(utf8));
        }

        // 3. 回退编码（默认 GBK）
        if (fallbackEncoding != null && !fallbackEncoding.isBlank()) {
            try {
                Charset fallback = Charset.forName(fallbackEncoding);
                String fallbackText = decodeStrict(fallback, data, 0);
                if (fallbackText != null) {
                    return new Detection(fallback.name(), false, dominantEol(fallbackText), binary, false,
                            normalizeForDisplay(fallbackText));
                }
            } catch (Exception ignored) {
                // 回退编码非法或不可用，继续走 Latin-1 预览
            }
        }

        // 4. Latin-1 只读预览
        String preview = new String(data, StandardCharsets.ISO_8859_1);
        return new Detection(StandardCharsets.ISO_8859_1.name(), false, dominantEol(preview), binary, true,
                normalizeForDisplay(preview));
    }

    /**
     * 按检测到的编码/EOL/BOM 重新编码。EOL 统一为给定类型，不做全文件 trim，保留末尾换行。
     */
    public static byte[] encode(String text, String encoding, boolean bom, Eol eol) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(encoding, "encoding");
        Eol target = eol == null ? Eol.LF : eol;
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        if (target != Eol.LF) normalized = normalized.replace("\n", target.chars());
        Charset charset = Charset.forName(encoding);
        byte[] body = normalized.getBytes(charset);
        if (!bom) return body;
        if (StandardCharsets.UTF_8.name().equals(charset.name())) {
            return prepend(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, body);
        }
        if (StandardCharsets.UTF_16LE.name().equals(charset.name())) {
            return prepend(new byte[]{(byte) 0xFF, (byte) 0xFE}, body);
        }
        if (StandardCharsets.UTF_16BE.name().equals(charset.name())) {
            return prepend(new byte[]{(byte) 0xFE, (byte) 0xFF}, body);
        }
        return body;
    }

    /**
     * 二进制判定：前 8KB 含 NUL，或不可打印字符（含 0x7F，排除 \t \n \r）比例 &gt; 30%。
     */
    public static boolean isBinary(byte[] data) {
        if (data == null || data.length == 0) return false;
        int scan = Math.min(data.length, BINARY_SCAN_LIMIT);
        int unprintable = 0;
        for (int i = 0; i < scan; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return true;
            if ((b < 0x20 && b != '\t' && b != '\n' && b != '\r') || b == 0x7F) unprintable++;
        }
        return (double) unprintable / scan > 0.30;
    }

    /** 统计 CRLF/LF/CR 数量取多数；全部为零或平票时回落 LF。 */
    public static Eol dominantEol(String text) {
        if (text == null || text.isEmpty()) return Eol.LF;
        int crlf = 0, lf = 0, cr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') { crlf++; i++; }
                else cr++;
            } else if (c == '\n') {
                lf++;
            }
        }
        if (crlf > lf && crlf > cr) return Eol.CRLF;
        if (cr > lf && cr > crlf) return Eol.CR;
        if (lf >= crlf && lf >= cr) return Eol.LF;
        return Eol.CRLF;
    }

    /** 把文本中的 EOL 全部归一化为 \n 供 TextArea 显示。 */
    public static String normalizeForDisplay(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 用指定编码严格解码（REPORT）；失败返回 null。offset 指向跳过的 BOM 字节数。
     * 供状态栏手动切换编码后重新解码使用。
     */
    public static String decodeStrictly(byte[] data, int offset, String encoding) {
        if (data == null) return null;
        try {
            CharsetDecoder decoder = Charset.forName(encoding).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(data, offset, Math.max(0, data.length - offset))).toString();
        } catch (Exception failure) {
            return null;
        }
    }

    /** 扩展名 → 语言名（状态栏显示与后续语法高亮用）。 */
    public static String languageOf(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "Java";
            case "py" -> "Python";
            case "js", "mjs", "cjs" -> "JavaScript";
            case "ts" -> "TypeScript";
            case "json" -> "JSON";
            case "xml", "fxml" -> "XML";
            case "html", "htm" -> "HTML";
            case "css" -> "CSS";
            case "sh", "bash", "zsh" -> "Shell";
            case "yml", "yaml" -> "YAML";
            case "md", "markdown" -> "Markdown";
            case "properties" -> "Properties";
            case "sql" -> "SQL";
            case "c", "h" -> "C";
            case "cpp", "hpp", "cc" -> "C++";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "conf", "cfg", "ini" -> "Config";
            case "log" -> "Log";
            case "txt" -> "Text";
            default -> null;
        };
    }

    private static String decodeStrict(Charset charset, byte[] data, int offset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(data, offset, data.length - offset)).toString();
        } catch (Exception failure) {
            return null;
        }
    }

    private static byte[] prepend(byte[] prefix, byte[] body) {
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }
}
