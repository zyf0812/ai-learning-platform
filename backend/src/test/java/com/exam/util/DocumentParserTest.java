package com.exam.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentParser 工具类测试。
 *
 * 覆盖范围：
 * 1. getFileType (getExt) 文件扩展名解析（含大小写、无扩展名、多点点）
 * 2. 纯文本/markdown 解析：UTF-8 正常情况
 * 3. 纯文本编码降级：UTF-8 出现大量 GBK 独有汉字时乱码 -> 自动用 GBK 重解析
 * 4. 不支持的文件格式抛异常（含正确扩展名）
 * 5. 空文件名边界
 *
 * 解析错误会导致 RAG 分块垃圾数据，直接影响 AI 答题和出题质量，
 * 属于"解析类复杂逻辑"高风险区。
 */
class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    // ========== getFileType 扩展名解析 ==========

    @Test
    void getFileType_recognizesPdf_lowercase() {
        assertEquals("pdf", parser.getFileType("notes.pdf"));
    }

    @Test
    void getFileType_recognizesUppercaseExt() {
        // ext 应被 toLowerCase
        assertEquals("pdf", parser.getFileType("NOTES.PDF"));
        assertEquals("docx", parser.getFileType("Report.DOCX"));
    }

    @Test
    void getFileType_handlesMultipleDots() {
        // 只取最后一个 . 之后的部分
        assertEquals("docx", parser.getFileType("my.file.v2.docx"));
        // archive.tar.gz -> 最后扩展名是 gz (tar 是前一个部分的尾)
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileType_noExt_defaultsToTxt() {
        // getExt: dot < 0 -> "txt"
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileType_dotAtStart_isTreatedAsNoExt() {
        // ".gitignore" 的 dot 在 index 0，getExt 要求 dot > 0，所以返回 "txt"
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileType_handlesAllSupportedExts() {
        assertEquals("pdf", parser.getFileType("a.PDF"));
        assertEquals("docx", parser.getFileType("a.Docx"));
        assertEquals("doc", parser.getFileType("a.doc"));
        assertEquals("pptx", parser.getFileType("a.PPTX"));
        assertEquals("ppt", parser.getFileType("a.ppt"));
        assertEquals("md", parser.getFileType("a.MD"));
        assertEquals("markdown", parser.getFileType("a.markdown"));
        assertEquals("txt", parser.getFileType("a.txt"));
    }

    // ========== 文本文件 UTF-8 解析 ==========

    @Test
    void parse_txtUtf8_returnsContent() {
        String content = "Hello, World! 这是 UTF-8 文本。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "note.txt");

        assertEquals(content, result);
    }

    @Test
    void parse_mdMarkdown_returnsContent() {
        String content = "# 标题\n\n段落内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        assertEquals(content, parser.parse(data, "doc.md"));
        assertEquals(content, parser.parse(data, "doc.markdown"));
    }

    // ========== 编码自动降级（UTF-8 乱码 -> GBK） ==========

    @Test
    void parse_txtGbkChinese_fallsBackToGbk() throws Exception {
        // 用 GBK 编码生成一段中文，若强制用 UTF-8 解码会产生大量 \ufffd（替换字符）
        // bad > length/10 触发降级到 GBK，最终还原为正确中文
        String original = "这是一段使用GBK编码的中文文本，用于测试自动编码检测降级功能是否正确工作";
        byte[] gbkData = original.getBytes(java.nio.charset.Charset.forName("GBK"));

        String result = parser.parse(gbkData, "chinese.txt");

        // 若降级逻辑正确，result 应等于原文
        assertEquals(original, result,
            "GBK 编码的中文文件应通过乱码检测自动降级为 GBK 解析");
    }

    @Test
    void parse_txtUtf8_noFalsePositiveFallback() {
        // 正常 UTF-8 短中文不应误触发降级
        String content = "Spring Boot 快速入门指南。\n第二节：配置详解。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "utf8-only.txt");

        // 正常 UTF-8 中文 decode 不会产生 \ufffd，结果应原样
        assertEquals(content, result);
    }

    // ========== 不支持的格式 ==========

    @Test
    void parse_unsupportedExt_throwsRuntimeException() {
        byte[] dummy = new byte[]{1, 2, 3};

        var ex1 = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "image.png"));
        assertTrue(ex1.getMessage().contains("不支持的文件格式"),
            "异常信息应包含扩展名: " + ex1.getMessage());
        assertTrue(ex1.getMessage().contains(".png"));

        var ex2 = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "archive.zip"));
        assertTrue(ex2.getMessage().contains(".zip"));

        var ex3 = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "script.js"));
        assertTrue(ex3.getMessage().contains(".js"));
    }

    // ========== 空输入 & 边界 ==========

    @Test
    void getFileType_emptyString_defaultsToTxt() {
        assertEquals("txt", parser.getFileType(""));
    }

    @Test
    void parse_emptyContent_txt() {
        byte[] data = new byte[0];
        String result = parser.parse(data, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parse_txt_whitespaceOnly_noGarbledChars() {
        // 全是空白字符，bad=0 -> 正常返回
        String content = "   \n\t\n  ";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "ws.txt"));
    }
}
