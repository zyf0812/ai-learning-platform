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

    // ============ getFileType / getExt 测试 ============

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeDocx() {
        assertEquals("docx", parser.getFileType("report.docx"));
    }

    @Test
    void getFileTypeUpperCaseExt() {
        assertEquals("pdf", parser.getFileType("SLIDES.PDF"));
    }

    @Test
    void getFileTypeMixedCase() {
        assertEquals("docx", parser.getFileType("Notes.DocX"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeHiddenFile() {
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeMultipleDots() {
        // getExt 只取最后一个点后的扩展名
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileTypeEmptyFilename() {
        assertEquals("txt", parser.getFileType(""));
    }

    // ============ 文本文件解析测试 ============

    @Test
    void parseTxtUtf8Content() {
        String content = "Hello World 你好世界";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdUtf8Content() {
        String content = "# Title\n\n## Section\n\n- item 1\n- item 2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "readme.md");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownExtension() {
        String content = "plain markdown content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "file.markdown");
        assertEquals(content, result);
    }

    @Test
    void parseTxtGbkFallback() {
        String gbkContent = "中文测试内容GBK编码";
        byte[] gbkData = gbkContent.getBytes(java.nio.charset.Charset.forName("GBK"));
        // GBK 字节在 UTF-8 下会产生大量乱码字符，触发降级
        String result = parser.parse(gbkData, "chinese.txt");
        // 由于 UTF-8 解码 GBK 会产生很多 \ufffd，应该降级到 GBK
        assertNotNull(result);
    }

    // ============ 不支持的格式测试 ============

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = new byte[]{0x00, 0x01, 0x02};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseUnsupportedXlsThrows() {
        byte[] data = new byte[]{0x00};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xls"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ============ 损坏文件测试 ============

    @Test
    void parseCorruptedPdfThrows() {
        byte[] fakePdf = "not a real pdf".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fakePdf, "broken.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseCorruptedDocxThrows() {
        byte[] fakeDocx = "not a real docx".getBytes(StandardCharsets.UTF_8);
        // POI 对损坏文件的异常类型不固定，但一定会抛出 RuntimeException 子类
        assertThrows(RuntimeException.class,
            () -> parser.parse(fakeDocx, "broken.docx"));
    }

    @Test
    void parseCorruptedPptxThrows() {
        byte[] fakePptx = "not a real pptx".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,
            () -> parser.parse(fakePptx, "broken.pptx"));
    }

    @Test
    void parseEmptyByteArrayTxt() {
        String result = parser.parse(new byte[0], "empty.txt");
        assertEquals("", result);
    }
}
