package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseTxtUtf8ReturnsContent() {
        String content = "Hello World\n测试内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseTxtGbkFallback() {
        String content = "中文测试内容";
        byte[] gbkData = content.getBytes(java.nio.charset.Charset.forName("GBK"));
        String result = parser.parse(gbkData, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdReturnsContent() {
        String content = "# Heading\n**bold** text";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "readme.md");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownReturnsContent() {
        String content = "## Section\n- list item";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "doc.markdown");
        assertEquals(content, result);
    }

    @Test
    void unsupportedFormatThrows() {
        byte[] data = "content".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(data, "test.xyz"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void getFileTypeReturnsExtension() {
        assertEquals("pdf", parser.getFileType("report.pdf"));
        assertEquals("docx", parser.getFileType("document.docx"));
        assertEquals("txt", parser.getFileType("readme.TXT"));
        assertEquals("ppt", parser.getFileType("presentation.PPT"));
    }

    @Test
    void getFileTypeWithoutExtensionReturnsTxt() {
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getExtHandlesEmptyFilename() {
        assertEquals("txt", parser.getFileType(""));
    }

    @Test
    void getExtHandlesOnlyDot() {
        assertEquals("txt", parser.getFileType("."));
    }

    @Test
    void parsePdfWithInvalidDataThrows() {
        byte[] invalidPdf = "not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> parser.parse(invalidPdf, "test.pdf"));
    }

    @Test
    void parseWordWithInvalidDataThrows() {
        byte[] invalidDocx = "not a docx".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> parser.parse(invalidDocx, "test.docx"));
    }

    @Test
    void parsePptWithInvalidDataThrows() {
        byte[] invalidPpt = "not a ppt".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> parser.parse(invalidPpt, "test.pptx"));
    }
}