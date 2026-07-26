package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseTxtUtf8() {
        String content = "Hello World\n测试内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseTxtGbk() {
        String content = "中文测试内容";
        byte[] data = content.getBytes(java.nio.charset.Charset.forName("GBK"));
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseTxtWithMixedEncoding() {
        String content = "Hello World";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void getFileType() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("txt", parser.getFileType("readme.txt"));
        assertEquals("md", parser.getFileType("README.md"));
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeCaseInsensitive() {
        assertEquals("pdf", parser.getFileType("Document.PDF"));
        assertEquals("docx", parser.getFileType("Report.Docx"));
    }

    @Test
    void unsupportedFormatThrows() {
        assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[0], "test.zip"));
    }

    @Test
    void emptyFilenameDefaultsToTxt() {
        String content = "test content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdown() {
        String content = "# Title\n\n**bold** text";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "readme.md");
        assertEquals(content, result);
    }
}