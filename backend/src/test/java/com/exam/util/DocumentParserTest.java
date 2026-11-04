package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesUtf8TextFile() {
        String content = "第一行\n第二行";
        assertEquals(content,
            parser.parse(content.getBytes(StandardCharsets.UTF_8), "notes.txt"));
    }

    @Test
    void fallsBackToGbkForMisdecodedText() {
        String content = "中文编码测试内容解析验证";
        assertEquals(content,
            parser.parse(content.getBytes(Charset.forName("GBK")), "gbk.txt"));
    }

    @Test
    void defaultsToTxtWhenFilenameHasNoExtension() {
        assertEquals("hello",
            parser.parse("hello".getBytes(StandardCharsets.UTF_8), "README"));
    }

    @Test
    void rejectsUnsupportedFileType() {
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "data.xlsx"));
        assertEquals("不支持的文件格式: .xlsx", ex.getMessage());
    }

    @Test
    void fileTypeIsLowercased() {
        assertEquals("pdf", parser.getFileType("report.PDF"));
        assertEquals("txt", parser.getFileType("README"));
    }
}
