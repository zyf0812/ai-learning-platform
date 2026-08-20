package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ==================== getFileType / getExt ====================

    @Test
    void getFileTypeReturnsExtensionLowercase() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
        assertEquals("docx", parser.getFileType("report.Docx"));
        assertEquals("txt", parser.getFileType("notes.txt"));
    }

    @Test
    void getFileTypeWithMultipleDotsUsesLastExtension() {
        assertEquals("docx", parser.getFileType("my.report.v2.docx"));
        assertEquals("tar.gz".substring("tar.gz".lastIndexOf('.') + 1),
            parser.getFileType("archive.tar.gz"));
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileTypeWithNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType("no_extension_file"));
    }

    @Test
    void getFileTypeWithDotAtStartTreatedAsNoExt() {
        // dot at position 0 means no real extension -> fallback to "txt"
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeWithTrailingDot() {
        // filename "file." → lastIndexOf('.') is at end, substring(dot+1) is empty.
        // Per getExt logic: dot > 0 → substring returns "" so extension is ""
        // Actually let's verify per code: filename.lastIndexOf('.') for "file." = 4
        // dot > 0 is true, substring(5) = ""
        assertEquals("", parser.getFileType("file."));
    }

    // ==================== Unsupported formats ====================

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"),
            "Expected unsupported format message, got: " + ex.getMessage());
    }

    @Test
    void parseUnsupportedXlsFormatThrows() {
        byte[] data = new byte[]{0x01, 0x02};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xls"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== Plain text / markdown parsing ====================

    @Test
    void parseTxtReturnsContent() {
        String content = "Hello, world! 你好，世界！";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "note.txt"));
    }

    @Test
    void parseMdReturnsContent() {
        String content = "# Title\n\nParagraph with **bold** text.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "readme.md"));
    }

    @Test
    void parseMarkdownExtensionWorks() {
        String content = "- list item 1\n- list item 2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "notes.markdown"));
    }

    @Test
    void parseTxtWithControlCharsOnlyWhitespace() {
        // newlines / tabs / carriage returns should NOT trigger bad-char counter
        String content = "Line1\nLine2\tindented\r\nLine3";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "clean.txt"));
    }

    // ==================== Invalid PDF / Word data throws (IOException wrapped) ====================

    @Test
    void parseInvalidPdfThrowsParseError() {
        byte[] notPdf = "not a real pdf content".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(notPdf, "fake.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"),
            "Expected parse failure wrapper, got: " + ex.getMessage());
    }

    @Test
    void parseInvalidDocxThrowsParseError() {
        byte[] notDocx = "random bytes".getBytes(StandardCharsets.UTF_8);
        // POI throws either IOException (wrapped as "文件解析失败") or POIXMLException (RuntimeException subclass)
        assertThrows(RuntimeException.class, () -> parser.parse(notDocx, "fake.docx"));
    }

    @Test
    void parseInvalidPptxThrowsParseError() {
        byte[] notPpt = "slides go here".getBytes(StandardCharsets.UTF_8);
        // POI throws either IOException (wrapped as "文件解析失败") or POIXMLException (RuntimeException subclass)
        assertThrows(RuntimeException.class, () -> parser.parse(notPpt, "slides.pptx"));
    }
}