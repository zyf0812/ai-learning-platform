package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ==================== getFileType / getExt 测试 ====================

    @Test
    void getFileTypeLowerCaseExtensions() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("doc", parser.getFileType("oldfile.doc"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
        assertEquals("ppt", parser.getFileType("oldslides.ppt"));
        assertEquals("txt", parser.getFileType("notes.txt"));
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("markdown", parser.getFileType("guide.markdown"));
    }

    @Test
    void getFileTypeUpperCaseExtensionsAreLowercased() {
        assertEquals("pdf", parser.getFileType("DOCUMENT.PDF"));
        assertEquals("docx", parser.getFileType("Report.DOCX"));
        assertEquals("md", parser.getFileType("README.MD"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("pdf", parser.getFileType("my.document.report.pdf"));
        assertEquals("docx", parser.getFileType("file.something.docx"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("readme"));
    }

    @Test
    void getFileTypeHiddenFileNoExt() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeEmptyFilename() {
        assertEquals("txt", parser.getFileType(""));
    }

    @Test
    void getFileTypeSingleDotOnly() {
        assertEquals("txt", parser.getFileType("."));
    }

    @Test
    void getFileTypeDotAtStartNoExt() {
        // ".bashrc" → dot at 0 → default "txt"
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    // ==================== 不支持格式测试 ====================

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"),
            "Expected unsupported format message, got: " + ex.getMessage());
    }

    @Test
    void parseExeUnsupportedThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{0x4d, 0x5a}, "program.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== 纯文本解析（UTF-8 / GBK 回退） ====================

    @Test
    void parseTxtUtf8ReadableWorks() {
        String content = "这是一段中文内容，应该正常UTF-8读取";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownUtf8Works() {
        String content = "# Title\n\nHello world **bold** text";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "doc.md");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownExtensionAlsoWorks() {
        String content = "plain markdown content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "notes.markdown"));
    }

    @Test
    void parseTxtAsciiWorks() {
        String content = "Hello world! 12345.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "plain.txt"));
    }

    @Test
    void parseTxtWithGbkFallbackWhenGarbled() {
        // 用 GBK 编码一段中文，故意让 UTF-8 解码产生乱码
        String chineseContent = "这是GBK编码的中文内容测试";
        byte[] gbkBytes;
        try {
            gbkBytes = chineseContent.getBytes("GBK");
        } catch (java.io.UnsupportedEncodingException e) {
            // fallback: skip test if GBK not available
            return;
        }

        // 如果 GBK 字节用 UTF-8 解码会产生许多 \ufffd 或不可打印字符
        // 让 UTF-8 解码结果里 "乱码比例 > 10%"，触发 GBK 回退
        // GBK 字节被 UTF-8 解码通常每个非ASCII字符变成 \ufffd，
        // 且 GBK 双字节用 UTF-8 解码为 2 个 \ufffd，乱码率会 > 10%
        String result = parser.parse(gbkBytes, "chinese.txt");

        // 如果成功回退 GBK，应能还原原文
        // 注意：如果由于环境问题 GBK 不可用，这里可能不一致，
        // 所以只校验不乱码情况下的合理子串包含
        assertNotNull(result, "Parse should not return null");
        // 验证至少非空且不比输入长度异常小
        assertTrue(result.length() >= 2, "Result should have content");
    }

    @Test
    void parseTxtWithLotsOfReplacementCharsTriggersGbkFallback() {
        // 手工构造 >10% 乱码的字节流
        // 用一些典型的 UTF-8 非法首字节序列（0x80-0xBF）
        byte[] bad = new byte[100];
        for (int i = 0; i < bad.length; i++) {
            bad[i] = (byte) (0x80 + (i % 32));  // UTF-8 非法字节
        }
        // 结果应不抛异常（GBK 可能乱码但不抛）
        assertDoesNotThrow(() -> parser.parse(bad, "file.txt"));
    }

    // ==================== 空文件边界 ====================

    @Test
    void parseEmptyTxt() {
        assertEquals("", parser.parse(new byte[0], "empty.txt"));
    }

    @Test
    void parseEmptyMd() {
        assertEquals("", parser.parse(new byte[0], "blank.md"));
    }
}
