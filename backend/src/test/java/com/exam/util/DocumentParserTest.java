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

    // ========== getFileType / 扩展名识别 ==========

    @Test
    void getFileTypeLowerCaseExtensions() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("doc", parser.getFileType("old.doc"));
        assertEquals("pptx", parser.getFileType("slide.pptx"));
        assertEquals("ppt", parser.getFileType("old.ppt"));
        assertEquals("md", parser.getFileType("notes.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
        assertEquals("txt", parser.getFileType("plain.txt"));
    }

    @Test
    void getFileTypeUpperCaseConvertedToLower() {
        assertEquals("pdf", parser.getFileType("DOCUMENT.PDF"));
        assertEquals("docx", parser.getFileType("Report.DocX"));
        assertEquals("md", parser.getFileType("NOTES.MD"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLastExtension() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("docx", parser.getFileType("my.report.v2.docx"));
        assertEquals("md", parser.getFileType("some.thing.else.md"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeHiddenFileWithNoExt() {
        // dotfile without extension: ".gitignore" → dot at 0 不算
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeHiddenFileWithExt() {
        assertEquals("md", parser.getFileType(".config.md"));
    }

    @Test
    void getFileTypeTrailingDotTreatedAsNoExt() {
        // "file." → lastIndexOf('.') at end, substring empty → 实际行为验证
        String result = parser.getFileType("file.");
        // 根据 getExt 实现：dot 在末尾 → substring(dot+1) 为空串（长度>0）
        assertEquals("", result);
    }

    // ========== 纯文本/Markdown 编码降级 ==========

    @Test
    void parseUtf8TextContentIsPreserved() {
        String content = "Hello World\n你好，世界！";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "note.md");
        assertEquals(content, result);
    }

    @Test
    void parseTxtFileSameAsMd() {
        String content = "Line 1\nLine 2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String r1 = parser.parse(data, "a.txt");
        String r2 = parser.parse(data, "a.md");
        String r3 = parser.parse(data, "a.markdown");
        assertEquals(content, r1);
        assertEquals(r1, r2);
        assertEquals(r2, r3);
    }

    @Test
    void parseGbkTextFallsBackWhenUtf8HasManyBadChars() throws Exception {
        // 构造 GBK 编码的中文内容：当用 UTF-8 解码时会产生大量 \ufffd（替换字符），触发降级
        String chinese = "这是一段中文测试内容";
        byte[] gbkBytes = chinese.getBytes(java.nio.charset.Charset.forName("GBK"));

        // GBK 字节用 UTF-8 解码会产生许多不可打印字符或 \ufffd，超过阈值时应降级为 GBK
        String result = parser.parse(gbkBytes, "chinese.txt");
        // 解析结果应等于中文原句（GBK 正确解码）
        assertEquals(chinese, result);
    }

    @Test
    void parseSmallUtf8WithFewBadCharsDoesNotFallback() {
        // 大部分字符是有效 UTF-8，仅一个坏字节不足 10%，不触发降级
        byte[] data = new byte[100];
        for (int i = 0; i < 100; i++) data[i] = (byte) 'A';
        data[50] = (byte) 0xFF; // 单个无效字节，比例 1% < 10%

        String result = parser.parse(data, "text.txt");
        // 保留 UTF-8 解码结果（包含 \ufffd 或 0xFF 对应的字符）
        assertEquals(100, result.length());
    }

    // ========== 不支持的格式 ==========

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] dummy = new byte[]{1, 2, 3};
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(dummy, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains(".zip"));
    }

    @Test
    void parseXlsxUnsupportedThrows() {
        byte[] dummy = new byte[]{1, 2, 3};
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(dummy, "sheet.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ========== 损坏的二进制文件抛出解析失败 ==========

    @Test
    void parseInvalidPdfBytesThrows() {
        // 非 PDF 内容的字节给 .pdf 扩展名，会触发 PDFBox 解析失败
        byte[] notPdf = "This is definitely not a PDF file".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(notPdf, "fake.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"),
                "实际消息: " + ex.getMessage());
    }

    @Test
    void parseInvalidDocxBytesThrows() {
        byte[] notDocx = "Random garbage content here".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(notDocx, "fake.docx"));
        assertTrue(ex.getMessage().contains("文件解析失败"),
                "实际消息: " + ex.getMessage());
    }

    @Test
    void parseInvalidPptxBytesThrows() {
        byte[] notPptx = "Not a powerpoint file at all".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(notPptx, "fake.pptx"));
        assertTrue(ex.getMessage().contains("文件解析失败"),
                "实际消息: " + ex.getMessage());
    }
}
