package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseReadsUtf8TextContent() {
        String content = "这是一段 UTF-8 中文内容\n第二行";

        assertEquals(content, parser.parse(content.getBytes(StandardCharsets.UTF_8), "notes.txt"));
    }

    @Test
    void parseFallsBackToGbkWhenUtf8DecodingIsGarbled() {
        String content = "这是一段用于验证GBK编码回退的中文文本内容";
        byte[] gbk = content.getBytes(Charset.forName("GBK"));

        assertEquals(content, parser.parse(gbk, "legacy.txt"));
    }

    @Test
    void parseRejectsUnsupportedExtension() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "archive.zip"));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains("zip"));
    }

    @Test
    void getFileTypeLowercasesExtension() {
        assertEquals("pdf", parser.getFileType("Report.PDF"));
        assertEquals("md", parser.getFileType("notes.MD"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypeDefaultsToTxtWhenExtensionMissing() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType(".gitignore"));
    }
}
