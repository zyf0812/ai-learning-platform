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

    // ==================== getFileType / getExt 边界条件 ====================

    @Test
    void getFileTypeLowerCaseExtension() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("txt", parser.getFileType("notes.txt"));
        assertEquals("md", parser.getFileType("readme.md"));
    }

    @Test
    void getFileTypeUpperCaseExtensionConvertedToLower() {
        assertEquals("pdf", parser.getFileType("DOCUMENT.PDF"));
        assertEquals("docx", parser.getFileType("Report.DOCX"));
        assertEquals("pptx", parser.getFileType("SLIDES.PPTX"));
    }

    @Test
    void getFileTypeMultipleDotsUsesLastExtension() {
        assertEquals("pdf", parser.getFileType("archive.tar.pdf"));
        assertEquals("txt", parser.getFileType("my.file.name.txt"));
        assertEquals("md", parser.getFileType("v1.0.2.release.md"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeEmptyFilename() {
        // lastIndexOf('.') == -1 → dot > 0 false → "txt"
        assertEquals("txt", parser.getFileType(""));
    }

    @Test
    void getFileTypeLeadingDotNotTreatedAsExtension() {
        // dot at position 0 → dot > 0 false → "txt"
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    // ==================== parse: 不支持的格式 ====================

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(data, "file.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== parse: .txt / .md UTF-8 ====================

    @Test
    void parseTxtUtf8Content() {
        String content = "Hello World, 你好世界！\n第二行内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownUtf8Content() {
        String md = "# 标题\n\n## 小节\n\n- 列表项1\n- 列表项2\n\n`代码`";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.md");
        assertEquals(md, result);
    }

    @Test
    void parseMarkdownExtensionAlternative() {
        String content = "markdown content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "file.markdown"));
    }

    // ==================== parse: .txt 编码降级 GBK ====================

    @Test
    void parseTxtHighBadCharRatioFallsBackToGbk() {
        // 构造一段包含大量不可打印字符的字节（非UTF-8），触发乱码检测 → 降级 GBK
        // 使用纯 GBK 编码的中文字符串："测试中文内容"
        String gbkText = "测试中文内容";
        byte[] gbkData = gbkText.getBytes(java.nio.charset.Charset.forName("GBK"));

        // GBK 字节用 UTF-8 解码会产生大量 \ufffd 或乱码，超过 10% 触发 GBK 降级
        String result = parser.parse(gbkData, "chinese.txt");

        // 降级后应该能正确还原 GBK 字符串
        assertEquals(gbkText, result);
    }

    @Test
    void parseTxtEmptyContent() {
        byte[] data = new byte[0];
        String result = parser.parse(data, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parseTxtOnlyControlCharsThatAreAllowed() {
        // \n \r \t 不计入乱码
        String content = "line1\nline2\r\n\tindented";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "ws.txt"));
    }
}
