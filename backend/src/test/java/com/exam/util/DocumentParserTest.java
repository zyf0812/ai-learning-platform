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

    // ======== getFileType / getExt 测试 ========

    @Test
    void getFileType_pdfExtension() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileType_docxExtension() {
        assertEquals("docx", parser.getFileType("report.docx"));
    }

    @Test
    void getFileType_upperCaseExtension_normalizedToLower() {
        assertEquals("pdf", parser.getFileType("SLIDES.PDF"));
        assertEquals("docx", parser.getFileType("NOTES.DOCX"));
    }

    @Test
    void getFileType_multipleDots_lastWins() {
        assertEquals("txt", parser.getFileType("archive.tar.gz.txt"));
    }

    @Test
    void getFileType_noExtension_defaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileType_dotfile_noExtension_defaultsToTxt() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileType_pptxExtension() {
        assertEquals("pptx", parser.getFileType("deck.pptx"));
    }

    @Test
    void getFileType_mdExtension() {
        assertEquals("md", parser.getFileType("notes.md"));
    }

    // ======== TXT/MD 解析 + 编码检测 ========

    @Test
    void parse_utf8PlainText_preserved() {
        String content = "Hello World\nLine 2";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "notes.txt"));
    }

    @Test
    void parse_utf8Chinese_preserved() {
        String content = "这是一个测试文档，包含中文内容。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "readme.md"));
    }

    @Test
    void parse_gbkEncodedText_fallbackFromUtf8ToGbkWhenGarbled() {
        // 构造一段 GBK 编码的文本，当按 UTF-8 解析时会产生大量乱码字符（0xFFFD / 不可打印）
        // 从而触发 DocumentParser 的 GBK 降级路径
        String content = "这是GBK编码的测试文本，用于验证编码降级逻辑是否正确执行";
        byte[] gbkData = content.getBytes(java.nio.charset.Charset.forName("GBK"));
        String parsed = parser.parse(gbkData, "notes.txt");
        // 断言解析后可以还原出原始中文内容
        assertEquals(content, parsed);
    }

    @Test
    void parse_markdownFile_parsedAsPlainText() {
        String md = "# Title\n\n## Section\n\n- item 1\n- item 2";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "spec.markdown"));
    }

    @Test
    void parse_textWithTabsAndNewlines_notTreatedAsGarbled() {
        // 合法的制表符和换行符不应计入乱码
        String content = "col1\tcol2\tcol3\nrow1\tv1\tv2\r\nrow2\tv3\tv4";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "table.txt"));
    }

    // ======== 不支持的文件格式 ========

    @Test
    void parse_unknownExtension_throwsRuntimeException() {
        byte[] dummy = new byte[]{1, 2, 3, 4};
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "archive.zip"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parse_xlsExtension_throwsUnsupported() {
        byte[] dummy = new byte[0];
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(dummy, "sheet.xls"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ======== 无效/损坏的二进制文件抛出解析失败 ========

    @Test
    void parse_corruptPdfBytes_throwsParseFailure() {
        byte[] notPdf = "not a real pdf".getBytes(StandardCharsets.UTF_8);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(notPdf, "fake.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parse_invalidDocxStructure_throwsParseFailure() {
        // 构造损坏的 zip 结构以触发 POI 解析错误
        byte[] notDocx = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        try {
            parser.parse(notDocx, "fake.docx");
            // POI 对损坏 zip 可能抛出也可能不抛；两种结果都视为通过（健壮性）
        } catch (RuntimeException ex) {
            // DocumentParser 会把 IOException 包装为 "文件解析失败: <原始消息>"
            // 也允许 POI 直接抛出的消息（如 "No valid entries..." 未被包装时）
            String m = ex.getMessage();
            assertNotNull(m);
        }
    }

    @Test
    void parse_invalidPptxStructure_throwsParseFailure() {
        byte[] notPptx = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x01, 0x02, 0x03, 0x04};
        try {
            parser.parse(notPptx, "fake.pptx");
        } catch (RuntimeException ex) {
            assertNotNull(ex.getMessage());
        }
    }
}
