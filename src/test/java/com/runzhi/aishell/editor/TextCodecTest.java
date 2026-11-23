package com.runzhi.aishell.editor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextCodec 单测（方案 §9）：UTF-8 BOM、UTF-16 BOM、无 BOM UTF-8、GBK、
 * CRLF/LF/CR、混合 EOL、NUL 二进制、末尾换行保留。
 */
class TextCodecTest {

    @Test
    void utf8BomIsDetectedAndStripped() {
        byte[] data = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        TextCodec.Detection detection = TextCodec.detect(data, "GBK");
        assertEquals(StandardCharsets.UTF_8.name(), detection.encoding());
        assertTrue(detection.bom());
        assertFalse(detection.binary());
        assertEquals("hi", detection.text());
    }

    @Test
    void utf16BomIsDetected() {
        byte[] le = {(byte) 0xFF, (byte) 0xFE, 'a', 0, 'b', 0};
        TextCodec.Detection leDetection = TextCodec.detect(le, "GBK");
        assertEquals(StandardCharsets.UTF_16LE.name(), leDetection.encoding());
        assertTrue(leDetection.bom());
        assertEquals("ab", leDetection.text());

        byte[] be = {(byte) 0xFE, (byte) 0xFF, 0, 'a', 0, 'b'};
        TextCodec.Detection beDetection = TextCodec.detect(be, "GBK");
        assertEquals(StandardCharsets.UTF_16BE.name(), beDetection.encoding());
        assertEquals("ab", beDetection.text());
    }

    @Test
    void plainUtf8WithoutBomIsDetected() {
        TextCodec.Detection detection = TextCodec.detect("中文内容".getBytes(StandardCharsets.UTF_8), "GBK");
        assertEquals(StandardCharsets.UTF_8.name(), detection.encoding());
        assertFalse(detection.bom());
        assertEquals("中文内容", detection.text());
    }

    @Test
    void gbkFallbackDecodesChinese() {
        byte[] gbk = "中文内容".getBytes(java.nio.charset.Charset.forName("GBK"));
        TextCodec.Detection detection = TextCodec.detect(gbk, "GBK");
        assertEquals("GBK", detection.encoding());
        assertFalse(detection.binary());
        assertEquals("中文内容", detection.text());
    }

    @Test
    void undecodableBytesFallBackToLatin1Preview() {
        byte[] data = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1, 'x'};
        TextCodec.Detection detection = TextCodec.detect(data, "Shift_JIS");
        assertTrue(detection.previewFallback());
        assertEquals(StandardCharsets.ISO_8859_1.name(), detection.encoding());
        assertEquals(5, detection.text().length());
    }

    @Test
    void crlfIsTheDominantEol() {
        TextCodec.Detection detection = TextCodec.detect("a\r\nb\r\nc\nd".getBytes(StandardCharsets.UTF_8), "GBK");
        assertEquals(TextCodec.Eol.CRLF, detection.eol());
        // 显示用文本已归一化为 \n
        assertEquals("a\nb\nc\nd", detection.text());
    }

    @Test
    void lfIsTheDominantEol() {
        TextCodec.Detection detection = TextCodec.detect("a\nb\nc\rd".getBytes(StandardCharsets.UTF_8), "GBK");
        assertEquals(TextCodec.Eol.LF, detection.eol());
        assertEquals("a\nb\nc\nd", detection.text());
    }

    @Test
    void loneCrIsTheDominantEol() {
        TextCodec.Detection detection = TextCodec.detect("a\rb\rc\nd".getBytes(StandardCharsets.UTF_8), "GBK");
        assertEquals(TextCodec.Eol.CR, detection.eol());
    }

    @Test
    void mixedEolFallsBackToLfOnTie() {
        TextCodec.Detection detection = TextCodec.detect("a\nb\r\nc".getBytes(StandardCharsets.UTF_8), "GBK");
        assertEquals(TextCodec.Eol.LF, detection.eol());
    }

    @Test
    void noEolDefaultsToLf() {
        TextCodec.Detection detection = TextCodec.detect("plain text".getBytes(StandardCharsets.UTF_8), "GBK");
        assertEquals(TextCodec.Eol.LF, detection.eol());
    }

    @Test
    void nulByteIsBinary() {
        TextCodec.Detection detection = TextCodec.detect(new byte[]{'a', 0, 'b'}, "GBK");
        assertTrue(detection.binary());
        assertEquals("a\0b", detection.text());
    }

    @Test
    void highUnprintableRatioIsBinary() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) data[i] = 0x01;
        TextCodec.Detection detection = TextCodec.detect(data, "GBK");
        assertTrue(detection.binary());
    }

    @Test
    void trailingNewlineIsPreservedOnEncode() {
        byte[] encoded = TextCodec.encode("line1\nline2\n", StandardCharsets.UTF_8.name(), false, TextCodec.Eol.LF);
        assertEquals("line1\nline2\n", new String(encoded, StandardCharsets.UTF_8));
    }

    @Test
    void encodePreservesBomAndEol() {
        byte[] encoded = TextCodec.encode("a\nb", StandardCharsets.UTF_8.name(), true, TextCodec.Eol.CRLF);
        assertEquals(3 + 4, encoded.length);
        assertEquals((byte) 0xEF, encoded[0]);
        assertEquals('a', encoded[3]);
        assertEquals('\r', encoded[4]);
        assertEquals('\n', encoded[5]);
        assertEquals('b', encoded[6]);

        byte[] gbk = TextCodec.encode("中文", "GBK", false, TextCodec.Eol.LF);
        assertEquals("中文", new String(gbk, java.nio.charset.Charset.forName("GBK")));
    }

    @Test
    void encodeNormalizesMixedEolToDominant() {
        byte[] encoded = TextCodec.encode("a\r\nb\rc", StandardCharsets.UTF_8.name(), false, TextCodec.Eol.CRLF);
        assertEquals("a\r\nb\r\nc", new String(encoded, StandardCharsets.UTF_8));
    }

    @Test
    void emptyAndNullDataAreSafe() {
        TextCodec.Detection empty = TextCodec.detect(new byte[0], "GBK");
        assertEquals("", empty.text());
        assertEquals(TextCodec.Eol.LF, empty.eol());
        assertFalse(TextCodec.isBinary(null));
    }

    @Test
    void languageDetectionCoversCommonExtensions() {
        assertEquals("Java", TextCodec.languageOf("Main.java"));
        assertEquals("Python", TextCodec.languageOf("script.py"));
        assertEquals("Shell", TextCodec.languageOf("deploy.sh"));
        assertNull(TextCodec.languageOf("noextension"));
    }

    @Test
    void decodeStrictlyReturnsNullOnMalformed() {
        byte[] utf8 = "中文".getBytes(StandardCharsets.UTF_8);
        assertNotNull(TextCodec.decodeStrictly(utf8, 0, "UTF-8"));
        byte[] truncated = new byte[utf8.length - 1];
        System.arraycopy(utf8, 0, truncated, 0, truncated.length);
        assertNull(TextCodec.decodeStrictly(truncated, 0, "UTF-8"));
    }
}
