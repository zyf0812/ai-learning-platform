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

    // --- 文件扩展名解析 ---

    @Test
    void getFileTypeWithStandardExtensions() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
        assertEquals("docx", parser.getFileType("report.docx"));
        assertEquals("doc", parser.getFileType("old.DOC"));
        assertEquals("pptx", parser.getFileType("slides.pptx"));
        assertEquals("ppt", parser.getFileType("old.PPT"));
        assertEquals("md", parser.getFileType("readme.md"));
        assertEquals("txt", parser.getFileType("notes.TXT"));
        assertEquals("markdown", parser.getFileType("readme.markdown"));
    }

    @Test
    void getFileTypeNoExtensionDefaultsToTxt() {
        assertEquals("txt", parser.getFileType("README"));
        assertEquals("txt", parser.getFileType("no-extension-file"));
    }

    @Test
    void getFileTypeWithDotPrefixDefaultsToTxt() {
        // lastIndexOf('.') == 0 → code treats dot at index 0 as "no ext" case → "txt"
        assertEquals("txt", parser.getFileType(".gitignore"));
        assertEquals("txt", parser.getFileType(".bashrc"));
    }

    @Test
    void getFileTypeWithMultipleDotsUsesLastExt() {
        assertEquals("gz", parser.getFileType("archive.tar.gz"));
        assertEquals("bak", parser.getFileType("report.docx.bak"));
        assertEquals("tmp", parser.getFileType("a.b.c.d.tmp"));
    }

    @Test
    void getFileTypeWithTrailingDotReturnsEmptyString() {
        // lastIndexOf('.') is at index > 0, so substring(dot+1) gives "" (past end)
        // Code does NOT fall back to "txt" because dot position was valid (dot > 0)
        assertEquals("", parser.getFileType("file."));
        assertEquals("", parser.getFileType("test.."));  // last dot at end
    }

    // --- 纯文本解析（不依赖PDF/Office库，走 md/txt/txt 路径） ---

    @Test
    void parseTxtWithValidUtf8() {
        String content = "Hello世界\n这是UTF-8文本";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "notes.txt");

        assertEquals(content, result);
    }

    @Test
    void parseMdWithUtf8Content() {
        String content = "# 标题\n\n段落内容\n\n## 二级标题";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "README.md");

        assertTrue(result.contains("# 标题"));
        assertTrue(result.contains("## 二级标题"));
    }

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = new byte[]{0x01, 0x02, 0x03};

        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(data, "image.png"));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void parseUnsupportedExtensionExeThrows() {
        byte[] data = new byte[]{0x4D, 0x5A, 0x00, 0x00};

        var ex = assertThrows(RuntimeException.class,
                () -> parser.parse(data, "program.exe"));

        assertTrue(ex.getMessage().contains(".exe"));
    }

    // --- 文本自动编码回退（GBK） ---

    @Test
    void parseTxtWithGbkEncodingFallsBackCorrectly() throws Exception {
        // GBK encoded Chinese that would show many replacement chars if read as UTF-8
        String gbkText = "这是一段使用GBK编码的中文文本，用于测试自动检测回退功能";
        byte[] gbkBytes = gbkText.getBytes("GBK");

        // When read as UTF-8, GBK bytes of Chinese chars mostly produce \ufffd (replacement char)
        // which triggers the GBK fallback threshold (> 10% bad chars)
        String result = parser.parse(gbkBytes, "chinese.txt");

        // If fallback works, we should recover a meaningful string
        assertNotNull(result);
        // The GBK decoded result should NOT be dominated by replacement chars
        long replacementCount = result.chars().filter(c -> c == 0xfffd).count();
        assertTrue(replacementCount < result.length() / 2,
                "Expected < 50% replacement chars after GBK fallback, got: " + replacementCount + "/" + result.length());
    }

    @Test
    void parseTxtPureAsciiNoFallback() {
        String ascii = "This is pure ASCII content 12345 !@#$%";
        byte[] data = ascii.getBytes(StandardCharsets.UTF_8);

        String result = parser.parse(data, "pure.txt");

        assertEquals(ascii, result);
    }

    // --- 边界：空文件 ---

    @Test
    void parseEmptyTxtFileReturnsEmptyString() {
        byte[] empty = new byte[0];

        String result = parser.parse(empty, "empty.txt");

        assertEquals("", result);
    }

    @Test
    void parseMarkdownEmptyString() {
        String result = parser.parse(new byte[0], "readme.markdown");
        assertEquals("", result);
    }
}
