package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传文档解析是所有学习流程的入口，编码探测与格式分支此前没有测试。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseUtf8TextFileReturnsContent() {
        byte[] data = "中文内容\n第二行".getBytes(StandardCharsets.UTF_8);

        assertEquals("中文内容\n第二行", parser.parse(data, "notes.txt"));
    }

    @Test
    void parseGbkEncodedTextFallsBackWhenUtf8IsGarbled() {
        byte[] data = "中文内容测试".getBytes(Charset.forName("GBK"));

        assertEquals("中文内容测试", parser.parse(data, "notes.txt"));
    }

    @Test
    void parseMarkdownExtensionReturnsContent() {
        byte[] data = "# 标题\n\n正文".getBytes(StandardCharsets.UTF_8);

        assertEquals("# 标题\n\n正文", parser.parse(data, "README.md"));
    }

    @Test
    void parseFilenameWithoutExtensionDefaultsToText() {
        byte[] data = "纯文本".getBytes(StandardCharsets.UTF_8);

        assertEquals("纯文本", parser.parse(data, "README"));
    }

    @Test
    void parseUnsupportedExtensionThrows() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse(new byte[]{1, 2}, "data.csv"));

        assertEquals("不支持的文件格式: .csv", ex.getMessage());
    }

    @Test
    void parseCorruptedPdfWrapsIOException() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> parser.parse("not a pdf".getBytes(StandardCharsets.UTF_8), "broken.pdf"));

        assertTrue(ex.getMessage().startsWith("文件解析失败"));
    }

    @Test
    void getFileTypeNormalizesCaseAndDefaultsToTxt() {
        assertEquals("docx", parser.getFileType("REPORT.DOCX"));
        assertEquals("pdf", parser.getFileType("scan.PDF"));
        assertEquals("txt", parser.getFileType("README"));
    }
}
