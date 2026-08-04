package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ===== getFileType / getExt =====

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("lecture.pdf"));
    }

    @Test
    void getFileTypeDocxUpperCase() {
        assertEquals("docx", parser.getFileType("Report.DOCX"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeHiddenFileNoExtDefaultsToTxt() {
        // dot-only at start with no other dot is treated as "no ext"
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileTypeMarkdownAliases() {
        assertEquals("md", parser.getFileType("notes.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
    }

    // ===== Unsupported format =====

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(data, "file.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ===== Plain text UTF-8 happy path =====

    @Test
    void parseTxtUtf8() {
        String text = "Hello World! 你好世界。\nSpring Boot 测试。";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "note.txt");
        assertEquals(text, result);
    }

    @Test
    void parseMdUsesTextPathUtf8() {
        String md = "# Title\n\n段落内容 with **markdown**.";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "readme.md"));
    }

    // ===== GBK fallback path: trigger high bad-char ratio =====

    @Test
    void parseTxtGbkFallbackWhenUtf8ProducesHighBadRatio() {
        // A GBK-encoded Chinese string that, when decoded as UTF-8,
        // produces many U+FFFD replacement chars triggering the fallback.
        String gbkText = "这是一段用GBK编码的中文文本，用于测试编码降级逻辑是否正确工作";
        byte[] gbkData = gbkText.getBytes(java.nio.charset.Charset.forName("GBK"));

        // Sanity check: as UTF-8, this produces lots of replacement chars
        // (ensures the test actually exercises the fallback branch)
        String asUtf8 = new String(gbkData, StandardCharsets.UTF_8);
        long badCount = asUtf8.chars().filter(c -> c == 0xfffd).count();
        assertTrue(badCount > 0, "Test precondition: GBK bytes as UTF-8 must produce replacement chars");

        String parsed = parser.parse(gbkData, "gbk-note.txt");
        // When decoded as GBK, the result should match the original Chinese text
        assertEquals(gbkText, parsed);
    }

    // ===== Empty txt content =====

    @Test
    void parseEmptyTxt() {
        assertEquals("", parser.parse(new byte[0], "empty.txt"));
    }
}
