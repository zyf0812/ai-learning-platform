package com.exam.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    @Nested
    @DisplayName("getFileType / getExt tests")
    class FileTypeTests {

        @Test
        @DisplayName("detects PDF extension")
        void detectsPdf() {
            assertEquals("pdf", parser.getFileType("document.pdf"));
        }

        @Test
        @DisplayName("detects DOCX extension")
        void detectsDocx() {
            assertEquals("docx", parser.getFileType("report.docx"));
        }

        @Test
        @DisplayName("detects PPTX extension")
        void detectsPptx() {
            assertEquals("pptx", parser.getFileType("slides.pptx"));
        }

        @Test
        @DisplayName("detects markdown extension")
        void detectsMarkdown() {
            assertEquals("md", parser.getFileType("notes.md"));
        }

        @Test
        @DisplayName("detects txt extension")
        void detectsTxt() {
            assertEquals("txt", parser.getFileType("plain.txt"));
        }

        @Test
        @DisplayName("defaults to txt when no extension")
        void defaultsToTxt() {
            assertEquals("txt", parser.getFileType("no-extension"));
        }

        @Test
        @DisplayName("handles multiple dots in filename")
        void handlesMultipleDots() {
            assertEquals("pdf", parser.getFileType("my.report.final.pdf"));
        }

        @Test
        @DisplayName("handles uppercase extensions")
        void handlesUppercase() {
            assertEquals("pdf", parser.getFileType("document.PDF"));
        }
    }

    @Nested
    @DisplayName("parse() tests")
    class ParseTests {

        @Test
        @DisplayName("parses plain text file")
        void parsesPlainText() {
            byte[] data = "Hello, World! This is a test document.".getBytes(StandardCharsets.UTF_8);
            String result = parser.parse(data, "test.txt");

            assertEquals("Hello, World! This is a test document.", result);
        }

        @Test
        @DisplayName("parses markdown file")
        void parsesMarkdown() {
            byte[] data = "# Title\n\nSome content".getBytes(StandardCharsets.UTF_8);
            String result = parser.parse(data, "test.md");

            assertEquals("# Title\n\nSome content", result);
        }

        @Test
        @DisplayName("parses markdown extension")
        void parsesMarkdownExtension() {
            byte[] data = "# Markdown Content".getBytes(StandardCharsets.UTF_8);
            String result = parser.parse(data, "test.markdown");

            assertEquals("# Markdown Content", result);
        }

        @Test
        @DisplayName("throws for unsupported file type")
        void throwsForUnsupportedType() {
            byte[] data = new byte[0];
            assertThrows(RuntimeException.class, () -> parser.parse(data, "test.xlsx"));
        }

        @Test
        @DisplayName("throws for unsupported file type without extension")
        void throwsForNoExtension() {
            byte[] data = new byte[0];
            assertThrows(RuntimeException.class, () -> parser.parse(data, "test.exe"));
        }

        @Test
        @DisplayName("handles empty text")
        void handlesEmptyText() {
            byte[] data = "".getBytes(StandardCharsets.UTF_8);
            String result = parser.parse(data, "empty.txt");

            assertEquals("", result);
        }

        @Test
        @DisplayName("handles Unicode characters")
        void handlesUnicode() {
            String unicodeText = "这是一段中文测试文本";
            byte[] data = unicodeText.getBytes(StandardCharsets.UTF_8);
            String result = parser.parse(data, "chinese.txt");

            assertEquals(unicodeText, result);
        }
    }
}