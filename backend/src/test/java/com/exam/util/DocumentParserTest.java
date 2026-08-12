package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentParser 测试：共享文档解析工具的文件类型判断、编码降级、异常处理。
 * PDF/Word/PPT 二进制解析依赖第三方库，此处集中覆盖纯文本路径与核心判定逻辑。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ========== 文件扩展名解析 ==========

    @Test
    void getFileTypeSimplePdfLowercase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeUppercaseConvertedToLower() {
        assertEquals("pdf", parser.getFileType("REPORT.PDF"));
        assertEquals("docx", parser.getFileType("Essay.DOCX"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        assertEquals("pdf", parser.getFileType("my.report.final.version.pdf"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeHiddenFileNoExtDefaultsToTxt() {
        // 以 . 开头但没有真正的扩展名部分，如 .gitignore
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeAllSupportedExts() {
        assertEquals("pdf", parser.getFileType("a.pdf"));
        assertEquals("docx", parser.getFileType("a.docx"));
        assertEquals("doc", parser.getFileType("a.doc"));
        assertEquals("pptx", parser.getFileType("a.pptx"));
        assertEquals("ppt", parser.getFileType("a.ppt"));
        assertEquals("md", parser.getFileType("a.md"));
        assertEquals("markdown", parser.getFileType("a.markdown"));
        assertEquals("txt", parser.getFileType("a.txt"));
    }

    // ========== 纯文本解析 (UTF-8 正常路径) ==========

    @Test
    void parseTxtUtf8ContentReadable() {
        String content = "Hello World\n这是UTF-8中文内容。\n第二行。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "notes.txt");

        assertEquals(content, result);
    }

    @Test
    void parseMarkdownUtf8Content() {
        String md = "# 标题\n\n段落1\n\n段落2\n";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "readme.md");

        assertEquals(md, result);
    }

    // ========== GBK 编码降级 ==========

    @Test
    void parseTxtWithGbkChineseFallsBackFromUtf8() {
        // 用 GBK 编码一段中文：UTF-8 解码后会产生大量 U+FFFD (�)
        String gbkContent = "这是一段使用GBK编码的中文文档内容。";
        byte[] gbkBytes = gbkContent.getBytes(Charset.forName("GBK"));

        // 验证 UTF-8 解码确实会产生乱码（用于确认测试数据有效）
        String asUtf8 = new String(gbkBytes, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < asUtf8.length(); i++) {
            char c = asUtf8.charAt(i);
            if (c == '\ufffd' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) bad++;
        }
        // GBK 中文被 UTF-8 解码会产生大量乱码，超过 10%
        assertTrue(bad > asUtf8.length() / 10,
            "测试数据乱码不足，bad=" + bad + "/" + asUtf8.length());

        String result = parser.parse(gbkBytes, "gbk-notes.txt");
        // 解析结果应为原始 GBK 内容
        assertEquals(gbkContent, result);
    }

    @Test
    void parseMarkdownFileWithGbkEncoding() {
        String gbkContent = "# GBK标题\n\nGBK段落内容。";
        byte[] gbkBytes = gbkContent.getBytes(Charset.forName("GBK"));

        String result = parser.parse(gbkBytes, "note.markdown");
        assertEquals(gbkContent, result);
    }

    // ========== 不支持格式异常 ==========

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"),
            "实际消息: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(".zip"));
    }

    @Test
    void parseXlsxUnsupportedThrows() {
        byte[] data = new byte[]{0x50, 0x4B, 0x03, 0x04};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "table.xlsx"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ========== UTF-8 正常不触发降级 ==========

    @Test
    void parseUtf8WithSomeControlCharsButNotEnoughForFallback() {
        // 内容中含少量 \t 等合法控制字符，不应触发 GBK 降级
        String utf8Content = "正常UTF-8文本\t包含制表符和换行\n以及少量合法字符。";
        byte[] data = utf8Content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "utf8.txt");

        // 降级后 GBK 解码 UTF-8 bytes 会变成乱码，这里比较应相等
        assertEquals(utf8Content, result);
    }

    @Test
    void parseLongUtf8DocumentNoFallback() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("第").append(i).append("段：这是一段正常的UTF-8中文内容，用于测试不会误触发GBK降级。\n\n");
        }
        String content = sb.toString();
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "long.txt");
        assertEquals(content, result);
    }

    // ========== 空内容 / 短内容 ==========

    @Test
    void parseEmptyTxtContent() {
        byte[] data = new byte[0];
        String result = parser.parse(data, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parseShortAsciiContent() {
        byte[] data = "OK".getBytes(StandardCharsets.UTF_8);
        assertEquals("OK", parser.parse(data, "test.md"));
    }
}
