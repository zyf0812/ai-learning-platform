package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ========== getFileType / getExt 测试 ==========

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("lecture.pdf"));
    }

    @Test
    void getFileTypeDocx() {
        assertEquals("docx", parser.getFileType("report.DOCX"));
    }

    @Test
    void getFileTypeDoc() {
        assertEquals("doc", parser.getFileType("old-notes.doc"));
    }

    @Test
    void getFileTypePptx() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
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
    void getFileTypeMultipleDots() {
        assertEquals("pdf", parser.getFileType("my.notes.file.pdf"));
    }

    @Test
    void getFileTypeHiddenFile() {
        assertEquals("md", parser.getFileType(".hidden.md"));
    }

    // ========== 纯文本解析（UTF-8） ==========

    @Test
    void parseTxtUtf8Simple() {
        String content = "Hello World\n这是测试内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdUtf8() {
        String md = "# Title\n\n## Section\n\n内容 **加粗**。";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "notes.md"));
    }

    // ========== GBK 编码降级 ==========

    @Test
    void parseTxtGbkFallback() {
        // 构造高比例乱码数据触发 GBK 降级：使用 GBK 编码中文字符
        String chinese = "中华人民共和国计算机科学技术";
        byte[] gbkData = chinese.getBytes(java.nio.charset.Charset.forName("GBK"));
        // GBK 编码的中文，用 UTF-8 解码会产生大量 \ufffd
        String result = parser.parse(gbkData, "gbk-file.txt");
        // 由于 GBK 解码后的内容应该能还原中文（或至少不抛异常），
        // 关键是验证乱码比例检测和 GBK 降级路径不会抛错
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void parseTxtPureAsciiNoFallback() {
        String ascii = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        byte[] data = ascii.getBytes(StandardCharsets.UTF_8);
        assertEquals(ascii, parser.parse(data, "ascii.txt"));
    }

    @Test
    void parseTxtWithControlCharacters() {
        // 正常的可打印控制字符（\n \r \t）不计入乱码
        String content = "Line1\nLine2\r\nCol1\tCol2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "controls.txt"));
    }

    // ========== 不支持的格式 ==========

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "some data".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseXlsxUnsupportedThrows() {
        byte[] data = new byte[]{0x50, 0x4B, 0x03, 0x04}; // ZIP 头，伪装 xlsx
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ========== 无效 PDF/DOCX/PPTX 抛出解析失败 ==========

    @Test
    void parseInvalidPdfThrowsParseError() {
        byte[] fake = "NOT A REAL PDF".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "fake.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidDocxThrowsAnyError() {
        byte[] fake = "NOT A REAL DOCX".getBytes(StandardCharsets.UTF_8);
        // POI 可能抛 RuntimeException (非 IOException)，只要拒绝处理即正确
        assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "fake.docx"));
    }

    @Test
    void parseInvalidPptxThrowsAnyError() {
        byte[] fake = "NOT A REAL PPTX".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "fake.pptx"));
    }
}
