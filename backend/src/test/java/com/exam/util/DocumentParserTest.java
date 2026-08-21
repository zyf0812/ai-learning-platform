package com.exam.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ==================== getFileType / getExt ====================

    @Test
    void getFileType_lowercaseExtensions() {
        assertEquals("pdf", parser.getFileType("lecture.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("doc", parser.getFileType("old.doc"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
        assertEquals("txt", parser.getFileType("notes.txt"));
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("markdown", parser.getFileType("doc.markdown"));
    }

    @Test
    void getFileType_caseInsensitive() {
        assertEquals("pdf", parser.getFileType("DATA.PDF"));
        assertEquals("docx", parser.getFileType("Report.DocX"));
    }

    @Test
    void getFileType_noExtensionDefaultsToTxt() throws Exception {
        Method getExt = DocumentParser.class.getDeclaredMethod("getExt", String.class);
        getExt.setAccessible(true);
        String result = (String) getExt.invoke(parser, "Makefile");
        assertEquals("txt", result);
    }

    @Test
    void getFileType_multipleDotsTakesLast() {
        assertEquals("tar.gz".contains(".") ? "gz" : "tar.gz",
            parser.getFileType("archive.tar.gz"));
    }

    // ==================== 纯文本解析 + 编码检测 ====================

    @Test
    void parse_plainUtf8Text_returnedDirectly() {
        String content = "Hello 世界! Spring Boot.\n第二行\n";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parse_plainMarkdownSameAsText() {
        String md = "# 标题\n\n段落内容\n\n- 列表项1\n- 列表项2";
        assertEquals(md, parser.parse(md.getBytes(StandardCharsets.UTF_8), "doc.md"));
        assertEquals(md, parser.parse(md.getBytes(StandardCharsets.UTF_8), "README.markdown"));
    }

    @Test
    void parse_unsupportedExtensionThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== GBK 编码降级（乱码特征检测） ====================

    @Test
    void parse_gbkChineseFallsBackFromUtf8() throws Exception {
        String chinese = "这是一段中文内容，用GBK编码保存，UTF-8解码时会产生大量乱码";
        byte[] gbkBytes = chinese.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 直接用 UTF-8 解码 GBK 字节，会产生很多 \ufffd（替换字符）
        String asUtf8 = new String(gbkBytes, StandardCharsets.UTF_8);
        int badCount = 0;
        for (int i = 0; i < asUtf8.length(); i++) {
            if (asUtf8.charAt(i) == '\ufffd') badCount++;
        }
        // 确认确实会产生乱码（否则测试用例无意义）
        assertTrue(badCount > asUtf8.length() / 10,
            "测试前提：GBK 字节以 UTF-8 解码应产生 >10% 乱码");

        // DocumentParser 应该检测到乱码并回退到 GBK
        String result = parser.parse(gbkBytes, "chinese.txt");
        assertEquals(chinese, result);
    }

    // ==================== 小字节文件边界 ====================

    @Test
    void parse_emptyTxtFile_handledGracefully() {
        assertEquals("", parser.parse(new byte[0], "empty.txt"));
    }
}
