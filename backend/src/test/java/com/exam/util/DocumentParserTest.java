package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseUtf8TextFile() {
        String content = "Hello World\n中文测试内容";
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseGbkTextFile() {
        String content = "中文GBK编码测试";
        byte[] data = content.getBytes(Charset.forName("GBK"));
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownFile() {
        String content = "# 标题\n\n**加粗文本**";
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.md");
        assertEquals(content, result);
    }

    @Test
    void unsupportedFormatThrowsException() {
        byte[] data = "content".getBytes();
        assertThrows(RuntimeException.class, () -> parser.parse(data, "test.exe"));
        assertThrows(RuntimeException.class, () -> parser.parse(data, "test.xyz"));
    }

    @Test
    void getFileTypeReturnsExtension() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("txt", parser.getFileType("readme.txt"));
        assertEquals("txt", parser.getFileType("noextension"));
        assertEquals("md", parser.getFileType("file.MD"));
    }

    @Test
    void emptyFilenameDefaultsToTxt() {
        assertEquals("txt", parser.getFileType(""));
        assertEquals("txt", parser.getFileType("."));
    }
}