package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档解析是上传流程的入口，解析失败或编码判断错误会直接导致文档内容损坏。
 * 覆盖纯文本编码判定、扩展名识别与不支持格式的显式失败。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesUtf8PlainText() {
        byte[] data = "第二章 面向对象\n封装与继承".getBytes(StandardCharsets.UTF_8);
        assertEquals("第二章 面向对象\n封装与继承", parser.parse(data, "notes.txt"));
    }

    @Test
    void parsesMarkdownAsPlainText() {
        byte[] data = "# 标题\n\n- 要点".getBytes(StandardCharsets.UTF_8);
        assertEquals("# 标题\n\n- 要点", parser.parse(data, "guide.md"));
    }

    @Test
    void fallsBackToGbkWhenUtf8ProducesGarbage() {
        String original = "中文测试内容";
        byte[] gbk = original.getBytes(Charset.forName("GBK"));

        // 以 UTF-8 解码 GBK 字节会产生替换字符，解析器应识别并降级到 GBK
        String decoded = parser.parse(gbk, "legacy.txt");

        assertEquals(original, decoded);
    }

    @Test
    void filenameWithoutExtensionIsTreatedAsText() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello", parser.parse(data, "README"));
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello", parser.parse(data, "README.TXT"));
        assertEquals("txt", parser.getFileType("README.TXT"));
    }

    @Test
    void unsupportedExtensionFailsFast() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "photo.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"), ex.getMessage());
    }

    @Test
    void corruptedPdfIsReportedAsParseFailure() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse("not a real pdf".getBytes(StandardCharsets.UTF_8), "broken.pdf"));
        assertTrue(ex.getMessage().startsWith("文件解析失败"), ex.getMessage());
    }

    @Test
    void getFileTypeDefaultsToTxtWithoutExtension() {
        assertEquals("txt", parser.getFileType("Makefile"));
        assertEquals("pdf", parser.getFileType("a.b.PDF"));
    }
}
