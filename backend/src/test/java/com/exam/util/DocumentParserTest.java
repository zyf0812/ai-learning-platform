package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesUtf8Text() {
        String content = "第一段\n\n第二段";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        assertEquals(content, parser.parse(bytes, "notes.txt"));
    }

    @Test
    void fallsBackToGbkWhenUtf8DecodingIsMostlyGarbage() {
        String content = "你好世界，这是中文内容。".repeat(10);
        byte[] gbk = content.getBytes(Charset.forName("GBK"));

        assertEquals(content, parser.parse(gbk, "legacy.txt"));
    }

    @Test
    void filenameWithoutExtensionIsTreatedAsText() {
        byte[] bytes = "plain".getBytes(StandardCharsets.UTF_8);

        assertEquals("plain", parser.parse(bytes, "README"));
    }

    @Test
    void emptyTextFileReturnsEmptyString() {
        assertEquals("", parser.parse(new byte[0], "empty.md"));
    }

    @Test
    void rejectsUnsupportedFileType() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "sheet.xlsx"));

        assertEquals("不支持的文件格式: .xlsx", ex.getMessage());
    }

    @Test
    void fileTypeIsLowercased() {
        assertEquals("pdf", parser.getFileType("REPORT.PDF"));
        assertEquals("docx", parser.getFileType("a.b.docx"));
        assertEquals("txt", parser.getFileType("no-ext"));
    }
}
