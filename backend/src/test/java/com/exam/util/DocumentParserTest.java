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

    @Test
    void getFileTypeReturnsExtensionLowercase() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
        assertEquals("docx", parser.getFileType("report.Docx"));
        assertEquals("txt", parser.getFileType("notes.txt"));
        assertEquals("md", parser.getFileType("readme.MD"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType("Makefile"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLastExtension() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("docx", parser.getFileType("my.report.docx"));
    }

    @Test
    void parseTxtFileReturnsContent() {
        String content = "Hello World\nThis is a test file.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");

        assertEquals(content, result);
    }

    @Test
    void parseMdFileReturnsContent() {
        String content = "# Title\n\nThis is **markdown** content.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "README.md");

        assertEquals(content, result);
    }

    @Test
    void parseMarkdownExtensionWorks() {
        String content = "# Heading\n\nParagraph text.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "notes.markdown");

        assertEquals(content, result);
    }

    @Test
    void parseGbkEncodedTextFallsBackFromUtf8() {
        String content = "这是一段中文测试内容";
        byte[] gbkData = content.getBytes(java.nio.charset.Charset.forName("GBK"));

        String result = parser.parse(gbkData, "test.txt");

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void parseUtf8TextDoesNotFallBack() {
        String content = "Normal UTF-8 text with no issues here.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");

        assertEquals(content, result);
    }

    @Test
    void parseUnsupportedFormatThrowsException() {
        byte[] data = new byte[]{1, 2, 3};

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "file.xyz"));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parsePdfEmptyDataThrowsException() {
        byte[] emptyData = new byte[0];

        assertThrows(RuntimeException.class,
            () -> parser.parse(emptyData, "test.pdf"));
    }

    @Test
    void parseDocxEmptyDataThrowsException() {
        byte[] emptyData = new byte[0];

        assertThrows(RuntimeException.class,
            () -> parser.parse(emptyData, "test.docx"));
    }

    @Test
    void parsePptxEmptyDataThrowsException() {
        byte[] emptyData = new byte[0];

        assertThrows(RuntimeException.class,
            () -> parser.parse(emptyData, "test.pptx"));
    }

    @Test
    void parseDocExtensionUsesWordParser() {
        byte[] emptyData = new byte[0];

        assertThrows(RuntimeException.class,
            () -> parser.parse(emptyData, "test.doc"));
    }

    @Test
    void parsePptExtensionUsesPptParser() {
        byte[] emptyData = new byte[0];

        assertThrows(RuntimeException.class,
            () -> parser.parse(emptyData, "test.ppt"));
    }

    @Test
    void getFileTypeWithDotAtStart() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeEmptyFilename() {
        assertEquals("txt", parser.getFileType(""));
    }
}
