package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ========== 文件类型识别 / 扩展名提取 ==========

    @Test
    void getFileTypePdfLowerCase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeDocxUpperCase() {
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileTypeDoc() {
        assertEquals("doc", parser.getFileType("paper.doc"));
    }

    @Test
    void getFileTypePptx() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypePpt() {
        assertEquals("ppt", parser.getFileType("deck.PPT"));
    }

    @Test
    void getFileTypeMd() {
        assertEquals("md", parser.getFileType("readme.md"));
    }

    @Test
    void getFileTypeMarkdown() {
        assertEquals("markdown", parser.getFileType("notes.markdown"));
    }

    @Test
    void getFileTypeTxt() {
        assertEquals("txt", parser.getFileType("data.txt"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("Makefile"));
    }

    @Test
    void getFileTypeHiddenFileNoExt() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("tar.gz", parser.getFileType("archive.tar.gz")); // 只看最后一个点
    }

    @Test
    void getFileTypePathLikeFilename() {
        assertEquals("java", parser.getFileType("com.example.MyClass.java"));
    }

    // ========== TXT/MD 文件解析 ==========

    @Test
    void parsePlainTextUtf8() {
        String content = "Hello, 你好，世界！\n这是一行中文文本。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");

        assertEquals(content, result);
    }

    @Test
    void parseMarkdownUtf8() {
        String content = "# 标题\n\n## 小节\n\n- 列表项\n- **粗体**\n";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "notes.md");

        assertEquals(content, result);
    }

    @Test
    void parseTxtWithWhitespaceAndTabs() {
        String content = "Line1\n\tindented\r\nLine3\n  spaces  ";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "file.txt");

        assertEquals(content, result);
    }

    // ========== 编码检测：GBK 降级 ==========

    @Test
    void parsePlainTextGbkChineseFallsBack() throws Exception {
        // GBK 编码的中文文本，用 UTF-8 解码会产生大量 \ufffd（替换字符）
        String content = "这是一段使用GBK编码的中文文本内容，用于测试编码自动降级功能。";
        byte[] gbkData = content.getBytes("GBK");

        String result = parser.parse(gbkData, "chinese.txt");

        // GBK 解码应该能还原内容
        assertEquals(content, result);
    }

    @Test
    void parseUtf8NoFalsePositiveGbk() {
        // 纯 ASCII 或正常 UTF-8 不应误触发 GBK 降级
        String content = "Normal text with ASCII and 一些中文 mixed together.";
        byte[] utf8Data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(utf8Data, "mixed.txt");

        assertEquals(content, result);
    }

    // ========== 不支持的文件格式 ==========

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = new byte[] { 0x00, 0x01, 0x02 };

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains(".zip"));
    }

    @Test
    void parseUnsupportedFormatXlsxThrows() {
        byte[] data = new byte[100];

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "spreadsheet.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ========== 空文件 / 极小文件 ==========

    @Test
    void parseEmptyTxt() {
        byte[] data = new byte[0];

        String result = parser.parse(data, "empty.txt");

        assertEquals("", result);
    }

    @Test
    void parseSingleByteTxt() {
        byte[] data = new byte[] { 'A' };

        String result = parser.parse(data, "a.txt");

        assertEquals("A", result);
    }
}
