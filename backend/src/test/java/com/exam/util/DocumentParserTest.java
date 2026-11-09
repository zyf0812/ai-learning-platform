package com.exam.util;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesUtf8PlainText() {
        String text = "你好，世界\n第二行";
        assertEquals(text, parser.parse(text.getBytes(StandardCharsets.UTF_8), "note.txt"));
    }

    @Test
    void defaultsToTextWhenFilenameHasNoExtension() {
        assertEquals("hello", parser.parse("hello".getBytes(StandardCharsets.UTF_8), "README"));
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        assertEquals("markdown", parser.getFileType("guide.MARKDOWN"));
        assertEquals("content", parser.parse("content".getBytes(StandardCharsets.UTF_8), "A.MD"));
    }

    @Test
    void rejectsUnsupportedExtension() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> parser.parse(new byte[]{1, 2, 3}, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains("zip"));
    }

    @Test
    void fallsBackToGbkWhenUtf8DecodingLooksGarbled() {
        String chinese = "中文内容测试数据样例文档解析降级处理";
        byte[] gbkBytes = chinese.getBytes(Charset.forName("GBK"));

        assertEquals(chinese, parser.parse(gbkBytes, "gbk.txt"));
    }

    @Test
    void parsesDocxContent() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Hello DOCX 内容");
            doc.write(out);
            docx = out.toByteArray();
        }

        String parsed = parser.parse(docx, "sample.docx");
        assertTrue(parsed.contains("Hello DOCX 内容"));
    }

    @Test
    void corruptPdfReportsParseFailure() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> parser.parse("not a pdf".getBytes(StandardCharsets.UTF_8), "broken.pdf"));
        assertTrue(ex.getMessage().startsWith("文件解析失败"));
    }
}
