package com.exam.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesUtf8TextWithoutFallback() {
        byte[] data = "Hello, 你好世界".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String text = parser.parse(data, "notes.txt");
        assertEquals("Hello, 你好世界", text);
    }

    @Test
    void fallsBackToGbkWhenUtf8LooksCorrupted() {
        // GBK bytes for "中文内容测试" cannot be decoded cleanly as UTF-8.
        byte[] gbk = "中文内容测试".getBytes(java.nio.charset.Charset.forName("GBK"));
        String text = parser.parse(gbk, "notes.md");
        assertEquals("中文内容测试", text);
    }

    @Test
    void markdownExtIsTreatedAsText() {
        byte[] data = "# Title\n\nbody".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("# Title\n\nbody", parser.parse(data, "doc.markdown"));
    }

    @Test
    void rejectsUnsupportedExtension() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "virus.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void unknownExtensionDefaultsToTxt() {
        byte[] data = "fallback content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("fallback content", parser.parse(data, "noext"));
    }

    @Test
    void uppercaseExtensionIsNormalized() {
        byte[] data = "Hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Hello", parser.parse(data, "README.TXT"));
    }

    @Test
    void getFileTypeReturnsLowercaseExtension() {
        assertEquals("pdf", parser.getFileType("Report.PDF"));
        assertEquals("docx", parser.getFileType("paper.DOCX"));
    }

    @Test
    void getFileTypeFallsBackToTxtWhenNoExtension() {
        assertEquals("txt", parser.getFileType("plainfile"));
    }

    @Test
    void corruptedBinaryDataRaisesParseFailure() {
        // Random bytes cannot be parsed as PDF.
        byte[] garbage = new byte[2048];
        new java.util.Random(42).nextBytes(garbage);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(garbage, "broken.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void corruptionDetectionRequiresMoreThanTenPercentBadChars() {
        // Mostly clean ASCII with a small number of stray non-printables - should keep UTF-8.
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = i < 5 ? (byte) 0x01 : (byte) 'a';
        }
        String text = parser.parse(data, "ok.txt");
        assertTrue(text.endsWith("aaaaa"));
    }
}