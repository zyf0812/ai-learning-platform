package com.exam.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    // ==================== getFileType / getExt 边界测试 ====================

    @Test
    void getFileTypeLowercaseExt() {
        assertEquals("pdf", parser.getFileType("report.pdf"));
        assertEquals("docx", parser.getFileType("notes.docx"));
        assertEquals("txt", parser.getFileType("readme.txt"));
    }

    @Test
    void getFileTypeUppercaseExtIsNormalized() {
        assertEquals("pdf", parser.getFileType("REPORT.PDF"));
        assertEquals("docx", parser.getFileType("Notes.DocX"));
    }

    @Test
    void getFileTypeNoExtDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeDotAtStartIsNotExt() {
        // 以点开头且没有后续点的文件名视为无扩展名
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        // lastIndexOf('.') 取最后一个点，所以 archive.tar.gz → gz
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("txt", parser.getFileType("my.notes.v2.txt"));
    }

    // ==================== TXT / MD UTF-8 解析 ====================

    @Test
    void parseTxtUtf8Content() {
        String text = "Hello 世界\nThis is a test.";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(text, result);
    }

    @Test
    void parseMarkdownUtf8Content() {
        String md = "# 标题\n\n- 列表项1\n- 列表项2\n\n**粗体**文本";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.md");
        assertEquals(md, result);
    }

    @Test
    void parseMarkdownExtensionAlternatives() {
        String content = "Alternative markdown ext";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "a.markdown"));
    }

    // ==================== TXT GBK 回退解析（乱码触发降级） ====================

    @Test
    void parseTxtGbkContentFallsBackWhenUtf8HasManyBadChars() {
        // 构造一段GBK编码的中文，用UTF-8解析会产生大量乱码字符(\ufffd或不可打印)
        String chineseText = "这是一段中文测试文本，用于验证编码降级逻辑";
        byte[] gbkData = chineseText.getBytes(Charset.forName("GBK"));

        String result = parser.parse(gbkData, "chinese.txt");
        // 降级到GBK解析后应能恢复正确中文
        assertEquals(chineseText, result);
    }

    // ==================== 不支持格式异常 ====================

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseXlsxUnsupportedThrows() {
        byte[] data = "not-really-xlsx".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== 空数据边界 ====================

    @Test
    void parseEmptyTxtDataReturnsEmpty() {
        byte[] empty = new byte[0];
        String result = parser.parse(empty, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parsePdfEmptyBytesThrowsParseError() {
        byte[] empty = new byte[0];
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(empty, "broken.pdf"));
        // PDFBox 可能抛 IOException（包装为"文件解析失败"）或其他 RuntimeException
        assertNotNull(ex.getMessage());
    }

    @Test
    void parseDocxEmptyBytesThrowsParseError() {
        byte[] empty = new byte[0];
        // POI 可能抛 IOException 或 POIXMLException(RuntimeException)，均视为解析失败
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(empty, "broken.docx"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void parsePptxEmptyBytesThrowsParseError() {
        byte[] empty = new byte[0];
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(empty, "broken.pptx"));
        assertNotNull(ex.getMessage());
    }
}
