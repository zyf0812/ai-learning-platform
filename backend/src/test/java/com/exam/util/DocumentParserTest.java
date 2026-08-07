package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ── getFileType / getExt ──────────────────────────────────────────

    @Test
    void getFileTypePdfLowercase() {
        assertEquals("pdf", parser.getFileType("lecture.pdf"));
    }

    @Test
    void getFileTypeDocxUppercaseExt() {
        assertEquals("docx", parser.getFileType("Report.DOCX"));
    }

    @Test
    void getFileTypeDoc() {
        assertEquals("doc", parser.getFileType("notes.doc"));
    }

    @Test
    void getFileTypePptx() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypePpt() {
        assertEquals("ppt", parser.getFileType("deck.ppt"));
    }

    @Test
    void getFileTypeMarkdown() {
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
    }

    @Test
    void getFileTypeTxtDefaultNoExt() {
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeHiddenFileNoExt() {
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("tar.gz".endsWith("gz") ? "gz" : "tar.gz",
            parser.getFileType("archive.tar.gz"));
    }

    // ── unsupported format rejection ─────────────────────────────────

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "file.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"),
            "Exception message should mention unsupported format, got: " + ex.getMessage());
    }

    @Test
    void parseUnsupportedImageFormatThrows() {
        byte[] data = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "photo.jpg"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ── plain text / markdown parsing ────────────────────────────────

    @Test
    void parseTxtReturnsContent() {
        String content = "Hello World\nLine 2\nLine 3";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "plain.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdReturnsContent() {
        String md = "# Title\n\nParagraph with **bold**.";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "doc.md"));
    }

    @Test
    void parseMarkdownExtension() {
        String md = "## Section\n\ntext";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "doc.markdown"));
    }

    // ── encoding fallback: high ratio of � chars → GBK ───────────────
    // (We simulate a "bad" UTF-8 string by building bytes that decode
    //  to lots of U+FFFD when interpreted as UTF-8. For simplicity we
    //  test the branch where content is clean UTF-8 → no fallback.)

    @Test
    void parseUtf8ChineseNoFallbackNeeded() {
        String chinese = "这是一段中文测试内容。Spring Boot 是优秀的Java框架。";
        byte[] data = chinese.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(chinese, result);
    }

    @Test
    void parseEmptyTxtFile() {
        byte[] data = new byte[0];
        assertEquals("", parser.parse(data, "empty.txt"));
    }

    @Test
    void parseTxtWithOnlyNewlinesAndTabs() {
        // control chars \n \r \t are allowed, should NOT trigger fallback
        String s = "\n\n\t\r\n";
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(s, parser.parse(data, "ws.txt"));
    }

    // ── corrupt binary data for supported formats should throw ───────

    @Test
    void parsePdfCorruptBytesThrowsParseError() {
        byte[] fake = "not a real pdf content".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"),
            "Expected parse failure message, got: " + ex.getMessage());
    }

    @Test
    void parseDocxCorruptBytesThrowsParseError() {
        byte[] fake = "not a docx".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.docx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parsePptxCorruptBytesThrowsParseError() {
        byte[] fake = "not a pptx".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.pptx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }
}
