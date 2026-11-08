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
    void getFileTypeIsLowerCaseAndDefaultsToTxt() {
        assertEquals("pdf", parser.getFileType("report.PDF"));
        assertEquals("gz", parser.getFileType("archive.tar.GZ"));
        assertEquals("txt", parser.getFileType("notes"));
        assertEquals("txt", parser.getFileType(".env"));
    }

    @Test
    void parsePlainTextKeepsUtf8Content() {
        String text = "# 标题\n这是正文内容";

        String parsed = parser.parse(text.getBytes(StandardCharsets.UTF_8), "note.MD");

        assertEquals(text, parsed);
    }

    @Test
    void parseChineseTextFallsBackToGbkWhenUtf8IsGarbled() {
        String text = "你好世界，这是中文测试内容";
        byte[] gbk = text.getBytes(Charset.forName("GBK"));

        String parsed = parser.parse(gbk, "gbk.txt");

        assertEquals(text, parsed);
    }

    @Test
    void parseUnsupportedExtensionThrowsClearError() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[] {1, 2, 3}, "data.xyz"));

        assertTrue(ex.getMessage().contains("不支持的文件格式: .xyz"));
    }

    @Test
    void parseDocxExtractsParagraphText() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Word 文档内容");
            doc.write(out);
            docx = out.toByteArray();
        }

        String parsed = parser.parse(docx, "material.docx");

        assertTrue(parsed.contains("Word 文档内容"));
    }
}
