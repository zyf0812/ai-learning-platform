package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentParser 负责解析上传文件，其 txt/md 分支有一段容易回归的编码降级逻辑：
 * 先按 UTF-8 解码，若乱码占比超 10% 则降级用 GBK 重新解码。
 * 此前无任何测试。这里覆盖扩展名识别、UTF-8 正常路径、GBK 降级路径与不支持的格式。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void getFileTypeDetectsExtensionLowercased() {
        assertEquals("pdf", parser.getFileType("doc.pdf"));
        assertEquals("docx", parser.getFileType("Report.DOCX"));
        assertEquals("md", parser.getFileType("notes.md"));
    }

    @Test
    void getFileTypeDefaultsToTxtWhenNoExtension() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void parseTxtWithUtf8ReturnsContent() {
        String content = "Hello 世界 line2";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        assertEquals(content, parser.parse(bytes, "notes.txt"));
    }

    /**
     * GBK 编码的中文文本按 UTF-8 解码会产生大量 U+FFFD 替换符，
     * 触发 >10% 乱码判定后应降级为 GBK 解码，从而还原原文。
     */
    @Test
    void parseGbkEncodedTxtFallsBackToGbk() {
        String original = "这是一段用于验证编码自动降级的中文测试内容";
        byte[] gbkBytes = original.getBytes(Charset.forName("GBK"));

        String result = parser.parse(gbkBytes, "notes.txt");
        assertEquals(original, result);
    }

    @Test
    void parseUnsupportedFormatThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "file.xyz"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }
}
