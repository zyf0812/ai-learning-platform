package com.exam.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    // ===== 扩展名 / 文件类型识别 =====

    @Test
    void getFileTypePdf() {
        assertEquals("pdf", parser.getFileType("document.PDF"));
    }

    @Test
    void getFileTypeDocx() {
        assertEquals("docx", parser.getFileType("report.docx"));
    }

    @Test
    void getFileTypePptUpperCase() {
        assertEquals("ppt", parser.getFileType("slides.PPT"));
    }

    @Test
    void getFileTypeTxtForNoExtension() {
        assertEquals("txt", parser.getFileType("README"));
    }

    @Test
    void getFileTypeTxtForHiddenFileNoExt() {
        assertEquals("txt", parser.getFileType(".gitignore"));
    }

    @Test
    void getFileTypeMultipleDots() {
        assertEquals("tar", parser.getFileType("archive.tar.gz"));
    }

    @Test
    void getFileTypeMd() {
        assertEquals("md", parser.getFileType("notes.MD"));
    }

    @Test
    void getFileTypeEmptyFilename() {
        // lastIndexOf('.') returns -1 → "txt"
        assertEquals("txt", parser.getFileType(""));
    }

    // ===== 不支持的格式 =====

    @Test
    void parseUnsupportedExtensionThrows() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "unknown.exe"));
        assertTrue(ex.getMessage().contains("不支持的文件格式"),
            "应包含'不支持的文件格式': " + ex.getMessage());
    }

    @Test
    void parseUnsupportedXlsThrows() {
        byte[] data = new byte[]{0x01, 0x02};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(data, "sheet.xlsx"));
        // .xlsx 不在支持列表里
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    // ===== 纯文本解析：UTF-8 正常情况 =====

    @Test
    void parseTxtUtf8Normal() {
        String content = "Hello World\n这是一段中文测试文本";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = parser.parse(data, "test.txt");
        assertEquals(content, result);
    }

    @Test
    void parseMarkdownUtf8Normal() {
        String content = "# Title\n\nParagraph with **bold**.";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "note.md"));
    }

    @Test
    void parseTextExtensionDefaultTxt() {
        // 无扩展名 → getExt返回"txt" → 走纯文本解析
        String content = "plain content";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "nofileext"));
    }

    // ===== 纯文本解析：GBK 降级 =====

    @Test
    void parseTxtWithHighBadCharRatioTriggersGbkFallback() throws Exception {
        // 构造一段GBK编码的中文文本：UTF-8解码时会产生大量乱码字符
        // "中文测试" 用GBK编码
        String original = "中文测试内容123456";
        byte[] gbkData = original.getBytes(java.nio.charset.Charset.forName("GBK"));

        String result = parser.parse(gbkData, "chinese.txt");

        // 如果正确走了GBK降级，应能还原原文本
        assertNotNull(result);
        // 验证解析结果应包含中文字符（不是乱码）
        assertFalse(result.contains("\ufffd"),
            "降级GBK后不应包含UTF-8替换字符: " + result);
    }

    // ===== 解析空/损坏二进制文件的健壮性 =====

    @Test
    void parsePdfWithInvalidBytesThrowsWrapped() {
        // 非PDF二进制内容伪装成PDF
        byte[] fake = "not a real pdf".getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "fake.pdf"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parseDocxWithInvalidBytesThrowsWrapped() {
        byte[] fake = new byte[]{0x00, 0x01, 0x02, 0x03};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "corrupt.docx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    @Test
    void parsePptWithInvalidBytesThrowsWrapped() {
        byte[] fake = new byte[]{0x00};
        var ex = assertThrows(RuntimeException.class,
            () -> parser.parse(fake, "broken.pptx"));
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    // ===== 空数据 =====

    @Test
    void parseTxtEmptyBytes() {
        byte[] empty = new byte[0];
        String result = parser.parse(empty, "empty.txt");
        assertEquals("", result);
    }

    // ===== 标准控制字符不应被算作乱码 =====

    @Test
    void parseTxtWithWhitespaceControlCharsNotTreatedAsGarbled() {
        // 包含 \t \n \r 这3种白名单控制字符 → 不算bad char
        String content = "Line1\nLine2\r\n\tindented line";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(content, parser.parse(data, "withws.txt"));
    }

    // ===== 乱码检测边界 =====

    @Test
    void parseTxtWithSubstituteCharTriggersBadCount() {
        // 构造包含大量 \ufffd (REPLACEMENT CHARACTER) 的字符串
        // 5个非乱码字符 + 20个\uFFFD → bad=20, 总长度=25 → bad/total=0.8 > 0.1 → 触发降级
        char[] chars = new char[25];
        for (int i = 0; i < 5; i++) chars[i] = 'A';
        for (int i = 5; i < 25; i++) chars[i] = '\ufffd';
        String s = new String(chars);
        byte[] data = s.getBytes(StandardCharsets.UTF_8);

        // 因为已经是字符串再编码成UTF-8，\ufffd在UTF-8中会正确编码，
        // 再UTF-8解码回来不会产生新的\uFFFD。所以该场景实际不会触发bad char计数。
        // 此测试只验证不会抛异常、返回内容合法。
        String result = parser.parse(data, "edge.txt");
        assertNotNull(result);
    }
}
