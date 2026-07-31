package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ---------- getExt / getFileType ----------

    @Test
    void getFileTypeExtractsPdfExtension() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
        assertEquals("pdf", parser.getFileType("report.pdf"));
    }

    @Test
    void getFileTypeExtractsDocxExtension() {
        assertEquals("docx", parser.getFileType("notes.DOCX"));
        assertEquals("docx", parser.getFileType("notes.docx"));
    }

    @Test
    void getFileTypeExtractsPptxExtension() {
        assertEquals("pptx", parser.getFileType("deck.pptx"));
        assertEquals("ppt", parser.getFileType("old.ppt"));
    }

    @Test
    void getFileTypeHandlesTxtMdMarkdown() {
        assertEquals("txt", parser.getFileType("readme.txt"));
        assertEquals("md", parser.getFileType("notes.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("noextension"));
        assertEquals("txt", parser.getFileType(".hiddenfile"));  // dot at position 0
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("docx", parser.getFileType("my.final.v2.report.DOCX"));
    }

    // ---------- Unsupported format ----------

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(data, "file.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ---------- Text file encoding detection ----------

    @Test
    void parseUtf8TextReturnsContent() {
        String content = "Hello 世界\nThis is a test.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "doc.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownAsText() {
        String md = "# Title\n\nSome **bold** text.";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "readme.md"));
    }

    @Test
    void parseGbkTextTriggersFallbackWhenHighBadChars() {
        // GBK encoding: create text that, when interpreted as UTF-8, produces many \ufffd
        // Construct a valid GBK byte sequence that is NOT valid UTF-8
        // GBK uses 2 bytes for Chinese with high bit set; UTF-8 strict decoding fails -> many ? replacement
        String chineseText = "这是一段中文测试文本";
        byte[] gbkBytes = chineseText.getBytes(java.nio.charset.Charset.forName("GBK"));

        // These GBK bytes read as UTF-8 will produce many \ufffd chars (>10%),
        // triggering the GBK fallback
        String result = parser.parse(gbkBytes, "notes.txt");
        assertEquals(chineseText, result);
    }

    @Test
    void parseValidUtf8NotFallbackedToGbk() {
        String utf8Text = "Normal text with some émojis and 中文";
        byte[] data = utf8Text.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "info.txt");
        // Valid UTF-8 should parse as UTF-8 correctly, no need to fallback
        assertEquals(utf8Text, result);
    }

    @Test
    void parseEmptyTextFile() {
        byte[] empty = new byte[0];
        String result = parser.parse(empty, "empty.txt");
        assertEquals("", result);
    }

    // ---------- Wrap runtime on IOException ----------

    @Test
    void parseInvalidPdfBytesThrowsRuntime() {
        byte[] notPdf = "not a real pdf structure".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> parser.parse(notPdf, "broken.pdf"));
    }

    @Test
    void parseInvalidDocxBytesThrowsRuntime() {
        byte[] notDocx = "plain text not zip/xml".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> parser.parse(notDocx, "broken.docx"));
    }

    @Test
    void parseInvalidPptxBytesThrowsRuntime() {
        byte[] notPptx = "some random data".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> parser.parse(notPptx, "broken.pptx"));
    }
}
