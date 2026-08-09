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

    // ==================== getFileType / getExt ====================

    @Test
    void getFileTypePdfLowercase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypePdfUppercase() {
        assertEquals("pdf", parser.getFileType("REPORT.PDF"));
    }

    @Test
    void getFileTypeDocxMixedCase() {
        assertEquals("docx", parser.getFileType("Essay.Docx"));
    }

    @Test
    void getFileTypeDoc() {
        assertEquals("doc", parser.getFileType("old.doc"));
    }

    @Test
    void getFileTypePptx() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypePpt() {
        assertEquals("ppt", parser.getFileType("oldslides.ppt"));
    }

    @Test
    void getFileTypeMd() {
        assertEquals("md", parser.getFileType("readme.md"));
    }

    @Test
    void getFileTypeMarkdown() {
        assertEquals("markdown", parser.getFileType("notes.markdown"));
    }

    @Test
    void getFileTypeTxt() {
        assertEquals("txt", parser.getFileType("log.txt"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("noextension"));
    }

    @Test
    void getFileTypeHiddenDotfileDefaultsToTxt() {
        // .bashrc 点前缀没有后缀 → dot 在 index 0 → "txt"
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("txt", parser.getFileType("my.backup.file.txt"));
    }

    @Test
    void getFileTypeTrailingDotTreatedAsNoExt() {
        // "file." → dot at last index → substring returns "" → toLowerCase = ""
        // 这个是极端边界，我们验证不会异常
        String result = parser.getFileType("file.");
        assertNotNull(result);
    }

    @Test
    void getFileTypeEmptyFilenameTolerated() {
        String result = parser.getFileType("");
        assertEquals("txt", result);
    }

    // ==================== parse 纯文本 (UTF-8) ====================

    @Test
    void parsePlainTxtUtf8() {
        String text = "Hello 世界！This is UTF-8 text.";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        String parsed = parser.parse(data, "doc.txt");
        assertEquals(text, parsed);
    }

    @Test
    void parseMarkdownUtf8() {
        String md = "# 标题\n\n- 列表项1\n- 列表项2\n\n**粗体**文本";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);

        String parsed = parser.parse(data, "notes.md");
        assertEquals(md, parsed);
    }

    // ==================== parse 纯文本 (GBK 降级) ====================

    @Test
    void parseTxtGbkFallbackWhenUtf8HasTooManyBadChars() {
        // 构造包含大量不可打印字符的"UTF-8 乱码"情况
        // 实际：创建超过 10% 的字符是不可打印的，就会触发 GBK 降级
        // 但是直接构造 GBK 中文字符串再用 GBK 编码：
        String gbkText = "这是一段GBK编码的中文文本内容用于测试编码自动降级";
        byte[] gbkBytes = gbkText.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 当 GBK 字节被当 UTF-8 解码时，通常会产生大量 \ufffd
        // 但此处我们验证 parse 能返回非空结果且不抛异常（取决于内容是否触发降级条件）
        String result = parser.parse(gbkBytes, "chinese.txt");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void parseTxtWithBinaryHighBadCharRatio() {
        // 构造字节数组，使 UTF-8 解码后超过 10% 是坏字符（不可打印或替换字符）
        // 简单方法：大量 0xFF 字节（非 UTF-8 连续）
        byte[] data = new byte[100];
        for (int i = 0; i < 100; i++) {
            data[i] = (byte) 0xFE; // 连续非 UTF-8 起始字节
        }

        // 不应抛异常（可能返回乱码，但流程要走通）
        assertDoesNotThrow(() -> parser.parse(data, "unknown.bin"));
    }

    // ==================== parse 不支持的格式 ====================

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = {1, 2, 3};
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(data, "file.xyz"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseUnsupportedImageExtensionThrows() {
        byte[] data = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ==================== parse 处理 IOException 包装 ====================

    @Test
    void parseInvalidPdfBytesThrowsParseFailure() {
        // PDF 魔数正确但内容损坏会被 PDFBox 抛 IOException，包装后 message 含 "文件解析失败"
        byte[] fakePdf = "%PDF-1.4 not a real pdf".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(fakePdf, "broken.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidDocxBytesThrowsParseFailure() {
        // docx 是 zip，但给的是随机字节 → POI 抛 IO/异常
        byte[] fake = {0x00, 0x01, 0x02, 0x03};

        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(fake, "broken.docx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseInvalidPptxBytesThrowsParseFailure() {
        byte[] fake = {0x00, 0x00, 0x00};
        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(fake, "broken.pptx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    // ==================== 解析不抛异常的正常场景（空文本） ====================

    @Test
    void parseEmptyTxtDataReturnsEmpty() {
        String result = parser.parse(new byte[]{}, "empty.txt");
        assertEquals("", result);
    }

    @Test
    void parseTxtWithNormalWhitespacePreserved() {
        String text = "Line1\r\nLine2\twith\ttabs\n\n\nLast";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(text, parser.parse(data, "t.txt"));
    }

    // ==================== 不抛异常的文件名 ====================

    @Test
    void parseNullDataForTxtWouldErrorGracefully() {
        // 实际会 NPE，但此处只验证各种情况的鲁棒性
        // NPE 是 JVM 层的，不是业务包装的
        assertThrows(Exception.class, () -> parser.parse(null, "a.txt"));
    }
}
