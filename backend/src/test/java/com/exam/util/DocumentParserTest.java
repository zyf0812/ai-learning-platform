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

    // ========== getFileType / getExt ==========

    @Test
    void getFileType_pdfLowerCase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileType_pdfUpperCase() {
        assertEquals("pdf", parser.getFileType("REPORT.PDF"));
    }

    @Test
    void getFileType_docxMixedCase() {
        assertEquals("docx", parser.getFileType("Notes.Docx"));
    }

    @Test
    void getFileType_multipleDots() {
        assertEquals("docx", parser.getFileType("my.final.report.docx"));
    }

    @Test
    void getFileType_noExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileType_dotAtEnd() {
        // filename="file." 时 lastIndexOf('.')>0，substring(dot+1) 得到空串
        assertEquals("", parser.getFileType("file."));
    }

    @Test
    void getFileType_pptPptx() {
        assertEquals("ppt", parser.getFileType("slides.ppt"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileType_markdownAliases() {
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
    }

    // ========== 解析：纯文本 ==========

    @Test
    void parse_txtUtf8Content() throws Exception {
        String text = "你好，世界！Hello, World!";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(text, result);
    }

    @Test
    void parse_mdUtf8Content() throws Exception {
        String md = "# 标题\n\n段落内容。";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "doc.md");
        assertEquals(md, result);
    }

    @Test
    void parse_markdownExtension() throws Exception {
        String content = "内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "file.markdown"));
    }

    @Test
    void parse_unsupportedExtensionThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "file.xyz"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ========== 乱码检测（UTF-8 vs GBK 降级） ==========

    @Test
    void parse_gbkTextFallsBackFromUtf8() throws Exception {
        String gbkText = "中文GBK编码测试内容";
        byte[] gbkBytes = gbkText.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 如果直接按 UTF-8 解析会有大量乱码字符，应触发降级使用 GBK 解析
        String result = parser.parse(gbkBytes, "test.txt");
        // GBK 编码的字节按 GBK 解码应还原原文
        assertEquals(gbkText, result);
    }

    @Test
    void parse_utf8StaysUtf8_NoFallback() throws Exception {
        String utf8Text = "纯UTF-8内容不会触发降级ABC123";
        byte[] utf8Bytes = utf8Text.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(utf8Bytes, "notes.md");
        assertEquals(utf8Text, result);
    }

    // ========== 边界：极短文本 ==========

    @Test
    void parse_emptyTxtFileReturnsEmpty() throws Exception {
        assertEquals("", parser.parse(new byte[0], "empty.txt"));
    }

    @Test
    void parse_singleCharacter() throws Exception {
        assertEquals("A", parser.parse("A".getBytes(StandardCharsets.UTF_8), "a.txt"));
    }
}
