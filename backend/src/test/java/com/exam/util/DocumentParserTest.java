package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ===== getFileType / getExt =====

    @Test
    void getFileTypePdfLowercase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeDocxUppercase() {
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileTypeMixedCase() {
        assertEquals("pptx", parser.getFileType("Slide.PpTx"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeDotAtStartIgnored() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileTypeMarkdownVariants() {
        assertEquals("md", parser.getFileType("notes.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
        assertEquals("txt", parser.getFileType("notes.txt"));
    }

    // ===== parse 文本文件 (UTF-8) =====

    @Test
    void parseTxtUtf8Content() {
        String content = "Hello 世界\n这是一行中文";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdUtf8Content() {
        String content = "# 标题\n\n段落内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "doc.md");
        assertEquals(content, result);
    }

    // ===== parse 不支持的格式 =====

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "something".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(data, "file.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ===== parse GBK 编码回退（触发乱码检测分支） =====

    @Test
    void parseTxtWithGbkFallbackWhenUtf8ProducesHighBadRatio() throws Exception {
        // GBK 编码的中文，用 UTF-8 解码会产生大量 \ufffd
        String chinese = "这是一段GBK编码的中文文本用于测试编码回退逻辑";
        byte[] gbkBytes = chinese.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 验证：纯 UTF-8 解码确实会产生高比例乱码
        String badUtf8 = new String(gbkBytes, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < badUtf8.length(); i++) {
            char c = badUtf8.charAt(i);
            if (c == '\ufffd' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) bad++;
        }
        // 前提条件：乱码比例 > 10%，确保走 GBK 分支
        assertTrue(bad > badUtf8.length() / 10,
            "测试前提不满足：乱码比例应为 " + (bad * 100 / badUtf8.length()) + "%");

        String result = parser.parse(gbkBytes, "test.txt");
        // GBK 解码应与原始内容一致
        assertEquals(chinese, result);
    }

    // ===== parse 空文件 =====

    @Test
    void parseEmptyTxt() {
        byte[] data = new byte[0];
        String result = parser.parse(data, "empty.txt");
        assertEquals("", result);
    }
}
