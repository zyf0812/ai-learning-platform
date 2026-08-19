package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // --- getExt / getFileType ---

    @Test
    void getFileTypeExtractsLowercaseExtension() {
        assertEquals("pdf", parser.getFileType("report.PDF"));
        assertEquals("docx", parser.getFileType("Essay.DOCX"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
        assertEquals("md", parser.getFileType("notes.md"));
        assertEquals("txt", parser.getFileType("readme.txt"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("no_extension_file"));
        assertEquals("txt", parser.getFileType(".bashrc")); // leading dot without ext
    }

    @Test
    void getFileTypeMultipleDotsUsesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("html", parser.getFileType("my.page.template.html"));
    }

    // --- Unsupported format ---

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // --- Text file encoding detection ---

    @Test
    void parsePlainTxtUtf8Normal() {
        String content = "Hello World\n这是一行中文\nSpring Boot测试";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMdFileUtf8() {
        String content = "# 标题\n\n段落内容\n\n- 列表1\n- 列表2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "readme.md");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownExtensionSameAsMd() {
        String content = "# Markdown Title";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "README.markdown");
        assertEquals(content, result);
    }

    @Test
    void parseTxtWithHighBadCharRateFallsBackToGbk() {
        // Construct a GBK-encoded string: Chinese text encoded in GBK will appear
        // as many \ufffd replacement chars when read as UTF-8, triggering the fallback.
        String chinese = "这是一段使用GBK编码的中文内容用于测试自动编码检测逻辑";
        byte[] gbkData = chinese.getBytes(java.nio.charset.Charset.forName("GBK"));

        String result = parser.parse(gbkData, "chinese_gbk.txt");
        // After GBK fallback, text should match original
        assertEquals(chinese, result);
    }

    @Test
    void parseTxtWithLowBadCharRateStaysUtf8() {
        // Normal ASCII + newline should not trigger GBK fallback
        String content = "package com.example;\n\npublic class Main {\n    public static void main(String[] args) {}\n}";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "Main.java.txt");
        assertEquals(content, result);
    }

    // --- PDF parsing error handling ---

    @Test
    void parseInvalidPdfThrowsParseError() {
        byte[] fakePdf = "This is not a real PDF file".getBytes(StandardCharsets.UTF_8);
        // PDFBox raises IOException for bad PDFs, which get wrapped in RuntimeException with message
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fakePdf, "fake.pdf"));
        assertNotNull(ex.getMessage());
    }

    // --- DOCX parsing error handling ---

    @Test
    void parseInvalidDocxThrowsParseError() {
        byte[] fakeDocx = "Not a valid Office Open XML structure".getBytes(StandardCharsets.UTF_8);
        // POI may throw a checked IOException (wrapped into "文件解析失败") or directly an
        // unchecked POI/Zip runtime exception depending on exact failure phase.
        assertThrows(RuntimeException.class,
            () -> parser.parse(fakeDocx, "broken.docx"));
    }

    // --- PPTX parsing error handling ---

    @Test
    void parseInvalidPptxThrowsParseError() {
        byte[] fakePptx = "Invalid powerpoint binary content here".getBytes(StandardCharsets.UTF_8);
        // Same caveat as DOCX: error may come wrapped or directly.
        assertThrows(RuntimeException.class,
            () -> parser.parse(fakePptx, "slides.pptx"));
    }
}
