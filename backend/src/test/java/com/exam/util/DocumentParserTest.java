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

    // ===== getExt / getFileType 测试 =====

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("lecture.pdf"));
    }

    @Test
    void getFileTypeDocxUpperCase() {
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileTypePptMixedCase() {
        assertEquals("pptx", parser.getFileType("Slides.PpTx"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeHiddenFileUnix() {
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeSingleDot() {
        assertEquals("pdf", parser.getFileType("lecture.notes.pdf"));
    }

    @Test
    void getFileTypeMultipleDotsLastWins() {
        // archive.tar.gz 取最后一个 . 后的 gz
        assertEquals("gz", parser.getFileType("my.archive.tar.gz"));
    }

    // ===== 纯文本解析测试 =====

    @Test
    void parseUtf8Txt() {
        String content = "Hello 你好 世界";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parseUtf8Markdown() {
        String md = "# 标题\n\n## 小节\n\n内容 here.";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "doc.md"));
    }

    @Test
    void parseMarkdownExtensionVariant() {
        String s = "markdown content";
        assertEquals(s, parser.parse(s.getBytes(StandardCharsets.UTF_8), "doc.markdown"));
    }

    @Test
    void parseGbkTextTriggersEncodingFallback() {
        // 构造一段 GBK 编码中文字符，UTF-8 解码会出现大量乱码
        String chinese = "这是一段中文测试内容用于验证编码降级功能";
        byte[] gbkData = chinese.getBytes(java.nio.charset.Charset.forName("GBK"));
        // 用 UTF-8 解码会产生很多不可打印字符，触发 fallback
        String result = parser.parse(gbkData, "gbk-file.txt");
        // GBK 解码回原文
        assertEquals(chinese, result);
    }

    @Test
    void parsePureAsciiNoFallback() {
        String ascii = "Hello World 123 !@#$%\nnewline";
        byte[] data = ascii.getBytes(StandardCharsets.UTF_8);
        assertEquals(ascii, parser.parse(data, "ascii.txt"));
    }

    // ===== 不支持格式抛出异常 =====

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseUnsupportedXlsThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1,2,3}, "sheet.xls"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ===== 损坏二进制抛出解析失败 =====

    @Test
    void parseInvalidPdfBytesThrows() {
        byte[] fake = {0x01, 0x02, 0x03, 0x04, 0x05};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidDocxBytesThrows() {
        byte[] fake = "not a real docx".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.docx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidPptxBytesThrows() {
        byte[] fake = {0x50, 0x4B, 0x03, 0x04, 0x00};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.pptx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }
}
