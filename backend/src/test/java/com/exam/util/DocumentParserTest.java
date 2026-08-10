package com.exam.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    // === getFileType() tests ===

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeDocx() {
        assertEquals("docx", parser.getFileType("report.docx"));
    }

    @Test
    void getFileTypePptx() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypeTxt() {
        assertEquals("txt", parser.getFileType("notes.txt"));
    }

    @Test
    void getFileTypeMd() {
        assertEquals("md", parser.getFileType("readme.md"));
    }

    @Test
    void getFileTypeNoExtension() {
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileTypeCaseInsensitive() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
    }

    // === parse() with text files ===

    @Test
    void parseTxtFileWithUtf8() {
        String content = "This is a test document with UTF-8 characters: 你好世界";
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");

        assertEquals(content, result);
    }

    @Test
    void parseMdFile() {
        String content = "# Title\n\nSome markdown content";
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.md");

        assertTrue(result.contains("Title"));
        assertTrue(result.contains("markdown"));
    }

    @Test
    void parseMarkdownExtension() {
        String content = "# Test markdown";
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.markdown");

        assertTrue(result.contains("Test markdown"));
    }

    @Test
    void parseEmptyFileReturnsEmpty() {
        byte[] data = new byte[0];

        String result = parser.parse(data, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "test".getBytes();

        assertThrows(RuntimeException.class, () -> parser.parse(data, "test.csv"));
        assertThrows(RuntimeException.class, () -> parser.parse(data, "test.xlsx"));
        assertThrows(RuntimeException.class, () -> parser.parse(data, "test.jpg"));
    }

    // === encoding detection tests ===

    @Test
    void parseDetectsGarbledUtf8AndFallsBackToGbk() {
        String garbledUtf8 = new String(new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0x82},
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] data = garbledUtf8.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");
        assertNotNull(result);
    }

    @Test
    void parseNormalUtf8DoesNotFallback() {
        String normalText = "Hello World 正常文本";
        byte[] data = normalText.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");
        assertEquals(normalText, result);
    }

    // === document type tests ===

    @Test
    void parsePdfRequiresValidPdfBytes() {
        byte[] notPdf = "This is not a PDF file".getBytes();
        assertThrows(RuntimeException.class, () -> parser.parse(notPdf, "fake.pdf"));
    }

    @Test
    void parseDocxRequiresValidDocx() {
        byte[] notDocx = "This is not a DOCX file".getBytes();
        assertThrows(RuntimeException.class, () -> parser.parse(notDocx, "fake.docx"));
    }

    @Test
    void parsePptxRequiresValidPptx() {
        byte[] notPptx = "This is not a PPTX file".getBytes();
        assertThrows(RuntimeException.class, () -> parser.parse(notPptx, "fake.pptx"));
    }
}