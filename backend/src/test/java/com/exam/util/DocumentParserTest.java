package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseTxtUtf8() {
        String content = "Hello World\n你好世界";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseTxtGbkFallback() {
        String content = "中文测试内容";
        byte[] data = content.getBytes(java.nio.charset.Charset.forName("GBK"));
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdFile() {
        String content = "# Title\n\n**bold** and *italic*";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "document.md");
        assertEquals(content, result);
    }

    @Test
    void getExtReturnsExtension() {
        assertEquals("pdf", parser.getFileType("file.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("txt", parser.getFileType("readme.txt"));
        assertEquals("txt", parser.getFileType("noextension"));
        assertEquals("txt", parser.getFileType(".hidden"));
    }

    @Test
    void getExtCaseInsensitive() {
        assertEquals("pdf", parser.getFileType("FILE.PDF"));
        assertEquals("docx", parser.getFileType("Report.DocX"));
    }

    @Test
    void unsupportedFormatThrows() {
        byte[] data = "test".getBytes();
        assertThrows(RuntimeException.class, () -> parser.parse(data, "file.xyz"));
    }

    @Test
    void parseWithEmptyFilename() {
        String content = "content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "");
        assertEquals(content, result);
    }

    @Test
    void parseWithNullFilename() {
        String content = "content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertThrows(StringIndexOutOfBoundsException.class, () -> parser.parse(data, null));
    }
}