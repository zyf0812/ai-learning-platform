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

    // --- getFileType / getExt 测试 ---

    @Test
    void getFileTypeExtractsCommonExtensions() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("doc", parser.getFileType("oldfile.doc"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
        assertEquals("ppt", parser.getFileType("oldslides.ppt"));
        assertEquals("txt", parser.getFileType("notes.txt"));
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("markdown", parser.getFileType("guide.markdown"));
    }

    @Test
    void getFileTypeIsCaseInsensitive() {
        assertEquals("pdf", parser.getFileType("DOCUMENT.PDF"));
        assertEquals("docx", parser.getFileType("Report.DocX"));
        assertEquals("md", parser.getFileType("README.MD"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("noextension"));
        assertEquals("txt", parser.getFileType(".bashrc")); // 点开头但无实际扩展名
    }

    @Test
    void getFileTypeMultipleDotsUsesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("docx", parser.getFileType("my.report.v2.docx"));
        assertEquals("pdf", parser.getFileType("a.b.c.d.pdf"));
    }

    @Test
    void getFileTypeEmptyFilename() {
        assertEquals("txt", parser.getFileType(""));
    }

    @Test
    void getFileTypeJustDot() {
        assertEquals("txt", parser.getFileType("."));
    }

    // --- parse() 纯文本格式测试 ---

    @Test
    void parseUtf8TxtFile() {
        String text = "这是UTF-8编码的中文内容。Hello World!";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");
        assertEquals(text, result);
    }

    @Test
    void parseUtf8MdFile() {
        String md = "# 标题\n\n这是一段Markdown内容。\n\n- 列表项1\n- 列表项2";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "readme.md");
        assertEquals(md, result);
    }

    @Test
    void parseUtf8MarkdownFile() {
        String md = "## Subtitle\nContent here.";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "notes.markdown"));
    }

    @Test
    void parseGbkEncodedTxtFallsBackFromUtf8() {
        // 构造一段 GBK 编码的中文内容，当用 UTF-8 解码时会出现大量乱码字符
        String original = "这是一段使用GBK编码的中文文本内容，用于测试编码降级功能是否正常工作。";
        byte[] gbkData = original.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 用 UTF-8 解码这段 GBK 数据，会产生很多 \ufffd（乱码替换符）
        // 当乱码比例超过 1/10 时，DocumentParser 会自动降级为 GBK
        String result = parser.parse(gbkData, "chinese.txt");

        // 结果应该等于原文（因为 GBK 编码的内容被正确识别并降级解码）
        assertEquals(original, result);
    }

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "file.xyz"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains(".xyz"));
    }

    @Test
    void parseUnsupportedExtensionCaseInsensitive() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "file.UNKNOWN"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // --- parse() PDF/Word/PPT 非法数据测试（确保抛出解析失败而非崩溃） ---

    @Test
    void parseInvalidPdfThrowsParseError() {
        byte[] invalidData = "This is not a real PDF file".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "fake.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidDocxThrowsParseError() {
        byte[] invalidData = "Not a docx".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "fake.docx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidPptxThrowsParseError() {
        byte[] invalidData = "Not a pptx at all".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "fake.pptx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    // --- parse() 空数据边界测试 ---

    @Test
    void parseEmptyTxtFile() {
        byte[] empty = new byte[0];
        String result = parser.parse(empty, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parseWhitespaceOnlyTxt() {
        String content = "   \n\n  \t  \n  ";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "ws.txt"));
    }

    // --- 边界情况：文件名中特殊字符 ---

    @Test
    void getFileTypeSpecialCharsInName() {
        assertEquals("pdf", parser.getFileType("我的文件(2024版)-最终版.pdf"));
        assertEquals("docx", parser.getFileType("report - v2 (final).docx"));
        assertEquals("txt", parser.getFileType("file with spaces.txt"));
    }
}
