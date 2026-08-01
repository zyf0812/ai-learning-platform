package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ---------- 文件扩展名提取 ----------

    @Test
    void getFileTypeExtractsExtensionLowercase() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
        assertEquals("docx", parser.getFileType("report.Docx"));
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("txt", parser.getFileType("notes.TXT"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType("no_extension_file"));
    }

    @Test
    void getFileTypeHiddenDotFileCorrectly() {
        // 以.开头但后面仍有扩展名
        assertEquals("md", parser.getFileType(".hidden.md"));
        // 只有.开头没有扩展名
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("docx", parser.getFileType("my.report.v2.docx"));
    }

    // ---------- 纯文本解析：UTF-8 ----------

    @Test
    void parseUtf8TextReturnsContent() {
        String content = "Hello 世界 这是UTF-8测试文本";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownTreatedAsUtf8Text() {
        String md = "# Title\n\n- item1\n- item2\n\n```java\nint x = 1;\n```";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);

        assertEquals(md, parser.parse(data, "doc.md"));
        assertEquals(md, parser.parse(data, "doc.markdown"));
    }

    // ---------- 纯文本解析：GBK降级 ----------

    @Test
    void parseGbkChineseFallsBackFromUtf8() {
        // 用GBK编码一段中文，UTF-8解码会产生大量乱码字符\ufffd，触发降级
        String gbkContent = "这是一段使用GBK编码的中文文本，用于测试编码自动降级功能";
        byte[] gbkData = gbkContent.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 如果直接用UTF-8解码GBK中文，会产生超过10%的坏字符 -> 应降级到GBK
        String result = parser.parse(gbkData, "test.txt");
        assertEquals(gbkContent, result);
    }

    // ---------- 不支持的格式 ----------

    @Test
    void parseUnsupportedExtensionThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains(".zip"));
    }

    @Test
    void parseExeExtensionThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{0x4d, 0x5a}, "program.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ---------- 空数据边界 ----------

    @Test
    void parseEmptyTextFile() {
        assertEquals("", parser.parse(new byte[0], "empty.txt"));
    }

    @Test
    void parseWhitespaceOnlyPreserved() {
        String ws = "   \n\t\n  ";
        assertEquals(ws, parser.parse(ws.getBytes(StandardCharsets.UTF_8), "ws.txt"));
    }

    // ---------- 文本：控制字符检测 ----------

    @Test
    void parseTextWithNormalControlCharsNewlineTabPreserved() {
        String s = "line1\nline2\ttab\r\nwinline";
        assertEquals(s, parser.parse(s.getBytes(StandardCharsets.UTF_8), "t.txt"));
    }
}
