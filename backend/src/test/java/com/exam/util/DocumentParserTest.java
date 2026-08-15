package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ---------- getFileType / 扩展名识别 ----------

    @Test
    void getFileTypePdfLowercase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeDocxUppercase() {
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileTypeMixedCasePpt() {
        assertEquals("ppt", parser.getFileType("Slides.PpT"));
    }

    @Test
    void getFileTypeMd() {
        assertEquals("md", parser.getFileType("notes.md"));
    }

    @Test
    void getFileTypeMarkdown() {
        assertEquals("markdown", parser.getFileType("readme.markdown"));
    }

    @Test
    void getFileTypeTxt() {
        assertEquals("txt", parser.getFileType("data.txt"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("Makefile"));
    }

    @Test
    void getFileTypeHiddenDotfile() {
        // ".bashrc" 的 lastIndexOf('.') = 0，不满足 dot>0，所以用默认 txt
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeMultipleDotsUsesLast() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
    }

    // ---------- 纯文本/Markdown解析 + 编码检测 ----------

    @Test
    void parseUtf8TextNoFallback() {
        String content = "Hello, 这是UTF-8编码的文本内容。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseUtf8MdNoFallback() {
        String content = "# 标题\n\n这是 **Markdown** 内容。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "doc.md");
        assertEquals(content, result);
    }

    @Test
    void parseGbkTextTriggersFallback() {
        // 构造一段 GBK 编码，用 UTF-8 解码会产生大量乱码（\ufffd 或不可打印字符>10%）
        // 最可靠的方法：直接用 GBK 编码一段中文，再验证能正确回退解析
        String original = "这是一段使用GBK编码的中文文本，用于测试编码降级功能是否正常工作。";
        byte[] gbkData = original.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 用 UTF-8 解码 GBK 字节流，会产生很多 \ufffd，触发 >10% 阈值
        String utf8Decoded = new String(gbkData, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < utf8Decoded.length(); i++) {
            char c = utf8Decoded.charAt(i);
            if (c == '\ufffd' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) bad++;
        }
        // 确认 > 10%，确保确实会触发 GBK 回退路径
        assertTrue(bad > utf8Decoded.length() / 10,
            "GBK bytes should decode to >=10% garbage under UTF-8, got " + bad + "/" + utf8Decoded.length());

        String result = parser.parse(gbkData, "note.txt");
        // GBK 回退后应与原文一致
        assertEquals(original, result);
    }

    @Test
    void parsePureAsciiWorks() {
        String ascii = "Hello World! 12345.\nLine 2.";
        byte[] data = ascii.getBytes(StandardCharsets.UTF_8);

        assertEquals(ascii, parser.parse(data, "readme.txt"));
    }

    // ---------- 不支持的格式 ----------

    @Test
    void parseUnsupportedFormatThrows() {
        byte[] dummy = new byte[]{1, 2, 3, 4};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(ex.getMessage().contains(".zip"));
    }

    @Test
    void parseXlsUnsupportedThrows() {
        byte[] dummy = new byte[]{1, 2, 3};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "sheet.xls"));
        assertTrue(ex.getMessage().contains("不支持的文件格式: .xls"));
    }

    // ---------- 损坏的PDF/Word/PPT抛出解析失败 ----------

    @Test
    void parseCorruptPdfThrowsRuntimeException() {
        byte[] notPdf = "This is not a PDF file".getBytes(StandardCharsets.UTF_8);
        // PDFBox 会抛出 IOException 或其子类
        assertThrows(Exception.class, () -> parser.parse(notPdf, "broken.pdf"));
    }

    @Test
    void parseCorruptDocxThrowsRuntimeException() {
        byte[] notDocx = "Hello world".getBytes(StandardCharsets.UTF_8);
        // POI 可能抛出 InvalidFormatException → 包装为 RuntimeException 或 POIXMLException
        assertThrows(Exception.class, () -> parser.parse(notDocx, "broken.docx"));
    }

    @Test
    void parseCorruptPptxThrowsRuntimeException() {
        byte[] notPptx = "garbage bytes here".getBytes(StandardCharsets.UTF_8);
        // POI 可能抛出各种异常
        assertThrows(Exception.class, () -> parser.parse(notPptx, "broken.pptx"));
    }
}
