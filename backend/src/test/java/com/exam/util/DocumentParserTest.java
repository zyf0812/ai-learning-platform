package com.exam.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    // ==================== 文件类型识别 ====================

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("lecture.pdf"));
        assertEquals("pdf", parser.getFileType("LECTURE.PDF"));
        assertEquals("pdf", parser.getFileType("path/to/file.Pdf"));
    }

    @Test
    void getFileTypeWord() {
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("doc", parser.getFileType("old-report.doc"));
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileTypePowerPoint() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
        assertEquals("ppt", parser.getFileType("deck.ppt"));
    }

    @Test
    void getFileTypeMarkdownAndText() {
        assertEquals("md", parser.getFileType("notes.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
        assertEquals("txt", parser.getFileType("readme.txt"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("Makefile"));
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("pdf", parser.getFileType("my.notes.final.v2.pdf"));
        assertEquals("txt", parser.getFileType("archive.tar.gz")); // tar.gz -> gz, but getExt returns last
    }

    // ==================== 不支持的格式抛出异常 ====================

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== 纯文本/Markdown 解析 + UTF-8 ====================

    @Test
    void parseTxtUtf8() {
        String content = "Hello, 世界! 这是一份学习笔记。\n第二行内容。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdUtf8() {
        String content = "# 标题\n\n- 列表项1\n- 列表项2\n\n**加粗文本**";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "guide.md");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownExtension() {
        String content = "## Section\nParagraph here.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "guide.markdown");
        assertEquals(content, result);
    }

    // ==================== GBK 编码降级检测 ====================

    @Test
    void parseTxtGbkChineseTriggersFallback() {
        // 构造含大量 GBK 特定字符的内容，用 UTF-8 解码会产生大量乱码（0xFFFD）
        // 触发 >10% 坏字符阈值，从而降级使用 GBK
        String gbkContent = "这是一段用GBK编码的中文文本内容，用于测试编码降级功能是否正常工作。";
        byte[] data = gbkContent.getBytes(java.nio.charset.Charset.forName("GBK"));
        // 用 UTF-8 解码 GBK 字节会产生很多 \ufffd，超过 10% 阈值
        String result = parser.parse(data, "gbk-notes.txt");
        // 降级到 GBK 后应能还原出原中文内容
        assertEquals(gbkContent, result);
    }

    @Test
    void parseTxtMostlyAsciiNoFallback() {
        // 大部分 ASCII 内容，UTF-8 解码无误，不应触发 GBK 降级
        String content = "package com.example;\n\npublic class Main { /* 注释 */ }";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "Main.java.txt");
        assertEquals(content, result);
    }

    @Test
    void parseTxtSmallAmountOfBadCharsBelowThreshold() {
        // 坏字符比例低于 10%，不触发降级（仍用 UTF-8 结果）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('a'); // 100 个 ASCII
        // 手动添加少量非 UTF-8 字节（让 UTF-8 解码产生少量坏字符）
        byte[] asciiBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBad = new byte[asciiBytes.length + 3];
        System.arraycopy(asciiBytes, 0, withBad, 0, asciiBytes.length);
        // 插入无效 UTF-8 字节 (0xFF 0xFE)，但 2 个坏字符 / ~103 字符 < 10%
        withBad[asciiBytes.length] = (byte) 0xFF;
        withBad[asciiBytes.length + 1] = (byte) 0xFE;
        withBad[asciiBytes.length + 2] = (byte) 0xFD;
        // 不抛异常即可，重点是不会触发阈值错误流程
        assertDoesNotThrow(() -> parser.parse(withBad, "mixed.txt"));
    }

    // ==================== getExt 边界行为 ====================

    @Test
    void getFileTypeEmptyFilename() {
        // 没有扩展名时默认 "txt"
        assertEquals("txt", parser.getFileType(""));
        assertEquals("txt", parser.getFileType("."));
    }

    @Test
    void getFileTypeCaseInsensitive() {
        assertEquals("pdf", parser.getFileType("FOO.PDF"));
        assertEquals("docx", parser.getFileType("foo.DocX"));
    }
}
