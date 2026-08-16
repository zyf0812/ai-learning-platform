package com.exam.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    // ==================== getFileType / getExt 测试 ====================

    @Test
    void getFileType_pdfExtension() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileType_docxExtension() {
        assertEquals("docx", parser.getFileType("report.docx"));
    }

    @Test
    void getFileType_docExtension() {
        assertEquals("doc", parser.getFileType("oldfile.doc"));
    }

    @Test
    void getFileType_pptxExtension() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileType_pptExtension() {
        assertEquals("ppt", parser.getFileType("oldslides.ppt"));
    }

    @Test
    void getFileType_txtExtension() {
        assertEquals("txt", parser.getFileType("notes.txt"));
    }

    @Test
    void getFileType_mdExtension() {
        assertEquals("md", parser.getFileType("readme.md"));
    }

    @Test
    void getFileType_markdownExtension() {
        assertEquals("markdown", parser.getFileType("guide.markdown"));
    }

    @Test
    void getFileType_uppercaseExtensionLowercased() {
        assertEquals("pdf", parser.getFileType("DOCUMENT.PDF"));
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileType_mixedCaseExtensionLowercased() {
        assertEquals("pdf", parser.getFileType("Doc.Pdf"));
    }

    @Test
    void getFileType_multipleDots() {
        assertEquals("pdf", parser.getFileType("my.report.file.pdf"));
    }

    @Test
    void getFileType_noExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileType_dotAtStartNoExt() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileType_justDot() {
        assertEquals("txt", parser.getFileType("."));
    }

    @Test
    void getFileType_emptyFilename() {
        assertEquals("txt", parser.getFileType(""));
    }

    // ==================== 纯文本格式解析测试（无文件依赖） ====================

    @Test
    void parse_txtFileUtf8Content() {
        String content = "Hello World\n这是中文测试\nLine 3";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "test.txt");

        assertEquals(content, result);
    }

    @Test
    void parse_mdFileUtf8Content() {
        String content = "# Title\n## Section\n- bullet 1\n- bullet 2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "readme.md");

        assertEquals(content, result);
    }

    @Test
    void parse_markdownFileUtf8Content() {
        String content = "Paragraph with **bold** and *italic*.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "guide.markdown");

        assertEquals(content, result);
    }

    @Test
    void parse_txtFileGbkContentFallsBack() {
        String content = "这是一段GBK编码的中文内容，用于测试编码降级";
        // 使用 GBK 编码，UTF-8 解析时会出现大量乱码字符
        byte[] data = content.getBytes(java.nio.charset.Charset.forName("GBK"));

        String result = parser.parse(data, "gbkfile.txt");

        // 应当能正确解析为原始内容
        assertEquals(content, result);
    }

    @Test
    void parse_txtFileValidUtf8NoFallback() {
        // UTF-8 正常内容，不应触发 GBK 降级
        String content = "Normal text with 中文 mixed together.\nNew line here.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "normal.txt");

        assertEquals(content, result);
    }

    @Test
    void parse_txtFileWithControlCharsWhitelist() {
        // 换行、回车、制表符应被允许，不计入乱码
        String content = "Line1\nLine2\r\nTab\tseparated";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "chars.txt");

        assertEquals(content, result);
    }

    @Test
    void parse_txtFileEmptyContent() {
        byte[] data = new byte[0];
        String result = parser.parse(data, "empty.txt");
        assertEquals("", result);
    }

    // ==================== 不支持的文件格式 ====================

    @Test
    void parse_unsupportedExtensionThrows() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parse_unsupportedXlsxThrows() {
        byte[] data = new byte[]{0x01, 0x02, 0x03};

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xlsx"));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== 损坏的 PDF/DOCX/PPTX 触发 IOException 路径 ====================

    @Test
    void parse_invalidPdfDataThrowsRuntimeException() {
        byte[] invalidData = "NOT A PDF FILE".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "broken.pdf"));

        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parse_invalidDocxDataThrowsRuntimeException() {
        byte[] invalidData = "NOT A DOCX".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "broken.docx"));

        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parse_invalidPptxDataThrowsRuntimeException() {
        byte[] invalidData = "NOT A PPTX".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "broken.pptx"));

        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parse_invalidDocDataThrowsRuntimeException() {
        byte[] invalidData = "NOT A DOC".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "broken.doc"));

        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parse_invalidPptDataThrowsRuntimeException() {
        byte[] invalidData = "NOT A PPT".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(invalidData, "broken.ppt"));

        assertTrue(ex.getMessage().contains("文件解析失败"));
    }
}
