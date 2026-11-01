package com.exam.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesUtf8TextFile() {
        String content = "学习笔记 第一章\nJava 基础";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        assertEquals(content, parser.parse(data, "notes.txt"));
    }

    @Test
    void defaultsUnknownExtensionToTxt() {
        assertEquals("hello", parser.parse("hello".getBytes(StandardCharsets.UTF_8), "README"));
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void normalizesUppercaseExtension() {
        assertEquals("md", parser.getFileType("NOTES.MD"));
        assertEquals("content", parser.parse("content".getBytes(StandardCharsets.UTF_8), "NOTES.MD"));
    }

    @Test
    void fallsBackToGbkWhenUtf8LooksGarbled() {
        String original = "这是一段用于验证编码回退的中文文档内容，包含标点符号与重复段落。"
                .repeat(3);
        byte[] gbk = original.getBytes(Charset.forName("GBK"));

        assertEquals(original, parser.parse(gbk, "legacy.txt"));
    }

    @Test
    void rejectsUnsupportedExtension() {
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(new byte[]{1, 2, 3}, "data.bin"));
        assertEquals("不支持的文件格式: .bin", ex.getMessage());
    }

    @Test
    void parsesDocxContent() throws Exception {
        byte[] data;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Hello DOCX");
            doc.write(out);
            data = out.toByteArray();
        }

        assertTrue(parser.parse(data, "sample.docx").contains("Hello DOCX"));
    }

    @Test
    void parsesPdfText() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Hello PDF");
                cs.endText();
            }
            doc.save(out);
            data = out.toByteArray();
        }

        assertTrue(parser.parse(data, "sample.pdf").contains("Hello PDF"));
    }
}
