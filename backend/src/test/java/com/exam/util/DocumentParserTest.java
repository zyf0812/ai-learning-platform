package com.exam.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档解析处于上传链路的核心位置，格式判断错误会直接导致后续出题/RAG 全部失效。
 * 这里覆盖扩展名判断、纯文本、PDF、DOCX 以及损坏文件的异常路径。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void fileTypeIsCaseInsensitiveAndDefaultsToTxt() {
        assertEquals("pdf", parser.getFileType("NOTES.PDF"));
        assertEquals("docx", parser.getFileType("report.DOCX"));
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType(".hidden"));
    }

    @Test
    void parsesUtf8PlainText() {
        String content = "中文内容\n第二行";
        String parsed = parser.parse(content.getBytes(StandardCharsets.UTF_8), "notes.txt");

        assertEquals(content, parsed);
    }

    @Test
    void parsesMarkdownUsingTextStrategy() {
        String content = "# 标题\n\n- 列表项";
        String parsed = parser.parse(content.getBytes(StandardCharsets.UTF_8), "README.md");

        assertEquals(content, parsed);
    }

    @Test
    void parsesPdfAndExtractsText() throws Exception {
        byte[] pdf = createPdf("Hello PDF");

        String parsed = parser.parse(pdf, "sample.pdf");

        assertTrue(parsed.contains("Hello PDF"));
    }

    @Test
    void parsesDocxAndExtractsText() throws Exception {
        byte[] docx = createDocx("Word 文档内容");

        String parsed = parser.parse(docx, "sample.docx");

        assertTrue(parsed.contains("Word 文档内容"));
    }

    @Test
    void unsupportedFormatThrowsWithExtensionInMessage() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "data.csv"));

        assertEquals("不支持的文件格式: .csv", ex.getMessage());
    }

    @Test
    void corruptPdfIsWrappedAsParseFailure() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2, 3}, "broken.pdf"));

        assertTrue(ex.getMessage().startsWith("文件解析失败"));
    }

    private byte[] createPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createDocx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
