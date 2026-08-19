package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        // Dummy values for external APIs; we only test pure logic methods.
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost:9999",
            "embedding-3", 1024);
    }

    // --- chunk() method tests ---

    @Test
    void chunkSplitsByParagraphs() {
        // Each paragraph ~30 chars (< chunkSize 50, no sentence split)
        // BUT each para + current > 50 triggers flush before append → each ends in separate chunk
        String p1 = repeat("甲", 30); // exactly 30 chars
        String p2 = repeat("乙", 30);
        String p3 = repeat("丙", 30);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;
        List<String> chunks = ragService.chunk(content, 50, 0);
        // p1 added alone (current empty, 30+newline ~ 32 chars)
        // p2: current(32) + 30 > 50 → flush p1 as chunk 1, start p2
        // p3: current(32) + 30 > 50 → flush p2 as chunk 2, start p3
        // End flush → chunk 3 = p3.  Total 3 chunks.
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).contains("甲"));
        assertTrue(chunks.get(1).contains("乙"));
        assertTrue(chunks.get(2).contains("丙"));
    }

    @Test
    void chunkIgnoresEmptyParagraphs() {
        String p1 = repeat("A", 30);
        String p2 = repeat("B", 30);
        // Surrounding blank lines / multiple blank lines should be ignored
        String content = "\n\n\n" + p1 + "\n\n\n\n" + p2 + "\n\n\n";
        List<String> chunks = ragService.chunk(content, 50, 0);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("A"));
        assertTrue(chunks.get(1).contains("B"));
    }

    @Test
    void chunkLongParagraphSplitsBySentence() {
        // Single long paragraph, many sentences, each sentence small
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("这是第").append(i).append("句短话。");
        }
        String content = sb.toString();

        List<String> chunks = ragService.chunk(content, 50, 0);
        assertTrue(chunks.size() >= 2);
        // Each chunk should not exceed chunkSize significantly
        for (String c : chunks) {
            assertTrue(c.length() <= 80, "Chunk too long: " + c.length());
        }
    }

    @Test
    void chunkMultipleParagraphsMergedUnderSize() {
        // Two short paragraphs should be merged into one chunk
        String content = "短段落A。\n\n短段落B。";
        List<String> chunks = ragService.chunk(content, 200, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("短段落A") && chunks.get(0).contains("短段落B"));
    }

    @Test
    void chunkWithOverlapAppendsNextChunkBeginning() {
        String p1 = "第一段内容非常长，绝对超过chunkSize=40，需要独立成分块。";
        String p2 = "第二段内容也很长足够做重叠，我们需要取它的开头做拼接。";
        String p3 = "第三段内容也很长足够独立，结尾部分不需要做重叠了。";
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;
        List<String> chunks = ragService.chunk(content, 40, 10);
        assertTrue(chunks.size() >= 2);
        // First chunk should contain p1 text AND the 10-char overlap start of p2
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段内容也很长足"));
    }

    @Test
    void chunkSingleChunkNoOverlap() {
        String content = "只有一段内容，不能分成多个块。";
        List<String> chunks = ragService.chunk(content, 200, 50);
        assertEquals(1, chunks.size());
    }

    @Test
    void chunkEmptyContentReturnsEmpty() {
        List<String> chunks = ragService.chunk("", 100, 10);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkWhitespaceOnlyReturnsEmpty() {
        List<String> chunks = ragService.chunk("   \n\n  \t  \n", 100, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkSentenceSplitRespectsPunctuationVariants() {
        // Mixed punctuation: Chinese 。！？ and English .!?
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("Sent").append(i);
            char p = switch (i % 4) {
                case 0 -> '.'; case 1 -> '!'; case 2 -> '?'; default -> '。';
            };
            sb.append(p);
        }
        List<String> chunks = ragService.chunk(sb.toString(), 40, 0);
        assertTrue(chunks.size() > 1);
    }

    // --- toVectorString() tests ---

    @Test
    void toVectorStringEmptyArray() {
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }

    @Test
    void toVectorStringSingleElement() {
        assertEquals("[1.5]", ragService.toVectorString(new float[]{1.5f}));
    }

    @Test
    void toVectorStringMultipleElements() {
        String result = ragService.toVectorString(new float[]{1.0f, 2.5f, -0.3f});
        assertEquals("[1.0,2.5,-0.3]", result);
    }

    @Test
    void toVectorStringLargeValues() {
        String result = ragService.toVectorString(new float[]{100.123f, -999.5f, 0.0f});
        assertEquals("[100.123,-999.5,0.0]", result);
    }

    // --- extractStructure() tests ---

    @Test
    void extractStructureEmptyChunksReturnsEmpty() {
        when(chunkMapper.getAllChunks("doc-empty")).thenReturn(List.of());
        String result = ragService.extractStructure("doc-empty");
        assertEquals("", result);
    }

    @Test
    void extractStructureReturnsFirstChunkStart() {
        String big = "这是文档开头的内容，它的长度足够超过五百个字符，我们需要验证它被截断到五百字符以内。"
            + "重复填充：这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。"
            + "这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。"
            + "这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。"
            + "这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。这是多余的文本。";
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(big));

        String result = ragService.extractStructure("doc1");
        assertTrue(result.contains("【文档开头】"));
        // Document start part must be truncated to 500 chars
        assertTrue(result.length() < 700);
    }

    @Test
    void extractStructureIdentifiesShortHeadings() {
        // Intro MUST be >= 200 chars so it is NOT identified as a heading
        String intro = repeat("长", 250);
        // Heading strings: must be 11..199 chars and no newlines
        String h1 = "第一章 Java面向对象基本概述";
        String h2 = "第二章 封装与访问控制详细说明";
        assertTrue(h1.length() > 10 && h1.length() < 200);
        assertTrue(h2.length() > 10 && h2.length() < 200);
        // Body paragraphs: >= 200 chars so they are NOT headings
        String body1 = repeat("正", 250);
        String body2 = repeat("文", 250);

        when(chunkMapper.getAllChunks("doc2")).thenReturn(List.of(intro, h1, body1, h2, body2));

        String result = ragService.extractStructure("doc2");
        assertTrue(result.contains("【章节标题】"));
        assertTrue(result.contains("第一章 Java面向对象基本概述"));
        assertTrue(result.contains("第二章 封装与访问控制详细说明"));
    }
}
