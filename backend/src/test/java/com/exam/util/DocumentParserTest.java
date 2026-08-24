package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ===== getExt / getFileType 测试 =====

    @Test
    void getFileTypePdfLowercase() {
        assertEquals("pdf", parser.getFileType("document.pdf"));
    }

    @Test
    void getFileTypeDocxUppercase() {
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
    }

    @Test
    void getFileTypeDoc() {
        assertEquals("doc", parser.getFileType("old-file.doc"));
    }

    @Test
    void getFileTypePptx() {
        assertEquals("pptx", parser.getFileType("slides.pptx"));
    }

    @Test
    void getFileTypePpt() {
        assertEquals("ppt", parser.getFileType("deck.ppt"));
    }

    @Test
    void getFileTypeMarkdownVariants() {
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("markdown", parser.getFileType("notes.markdown"));
        assertEquals("txt", parser.getFileType("log.txt"));
    }

    @Test
    void getFileTypeNoExtensionFallsBackToTxt() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeHiddenFileDotPrefix() {
        // .gitignore 没有扩展名（dot 在开头）
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeMultipleDotsTakesLast() {
        // archive.tar.gz: 最后一个点后面是 gz
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        // my.file.name.with.many.dots.txt: 最后一个点后面是 txt
        assertEquals("txt", parser.getFileType("my.file.name.with.many.dots.txt"));
    }

    // ===== 不支持的文件格式测试 =====

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "image.png"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseUnsupportedXlsThrows() {
        byte[] data = new byte[]{0x01, 0x02};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xlsx"));
        // xlsx 未在支持列表中（虽然是 poi 但没实现），应抛出不支持或解析失败
        assertNotNull(ex.getMessage());
    }

    // ===== 文本文件编码检测测试 =====

    @Test
    void parseTxtValidUtf8ReturnsContent() {
        String content = "Hello, 这是 UTF-8 编码的文本内容。\n第二行。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "notes.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownUtf8Content() {
        String md = "# 标题\n\n## 小节\n\n段落 **粗体** `code`";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        assertEquals(md, parser.parse(data, "doc.md"));
    }

    @Test
    void parseTxtGbkFallbackWhenUtf8HasManyBadChars() {
        // 构造一段 GBK 编码的中文，当按 UTF-8 解码时会产生大量 \ufffd
        String chineseText = "这是一段中文测试文本，使用GBK编码保存。";
        byte[] gbkData = chineseText.getBytes(java.nio.charset.Charset.forName("GBK"));

        // 验证：按 GBK 解码应该能恢复
        String decodedAsGbk = new String(gbkData, java.nio.charset.Charset.forName("GBK"));
        assertEquals(chineseText, decodedAsGbk);

        // DocumentParser 应该检测到乱码并回退到 GBK
        String result = parser.parse(gbkData, "gbk-file.txt");
        assertEquals(chineseText, result);
    }

    @Test
    void parseTxtWithSpecialCharactersUtf8() {
        String content = "特殊字符: @#$%^&*()_+-=[]{}|;':\",./<>?\n制表符\t和换行";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "special.txt"));
    }

    @Test
    void parseEmptyTextFileReturnsEmpty() {
        byte[] data = new byte[0];
        assertEquals("", parser.parse(data, "empty.txt"));
    }

    // ===== PDF / Word / PPT 解析测试（错误路径）=====

    @Test
    void parseInvalidPdfBytesThrowsParseError() {
        byte[] fakePdf = "not a real pdf".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fakePdf, "broken.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"),
            "Actual message: " + ex.getMessage());
    }

    @Test
    void parseInvalidDocxBytesThrowsAnyRuntime() {
        // POI 对于某些伪造字节可能抛出不同类型异常（ZipException 等）
        // 核心是：非 docx 字节不应该解析成功，必须抛出 RuntimeException
        byte[] fake = "random garbage data that is definitely not a valid docx zip file"
            .getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "test.docx"));
    }

    @Test
    void parseInvalidPptxBytesThrowsAnyRuntime() {
        byte[] fake = "not pptx binary data, just plain text".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "deck.pptx"));
    }

    // ===== 边界条件 =====

    @Test
    void getFileTypeEmptyFilename() {
        // 空文件名没有点，fallback 到 txt
        assertEquals("txt", parser.getFileType(""));
    }

    @Test
    void getFileTypeJustDot() {
        assertEquals("txt", parser.getFileType("."));
    }

    @Test
    void parseWhitespaceOnlyTextFile() {
        String content = "   \n\t  \r\n  ";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "whitespace.txt"));
    }
}
