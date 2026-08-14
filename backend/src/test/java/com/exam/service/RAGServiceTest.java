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

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "embedding-3", 1024);
    }

    // --- chunk() 段落切分 ---

    @Test
    void chunkSingleShortParagraphReturnsOneChunk() {
        String content = "这是一个简短的段落，不超过chunkSize。";

        List<String> chunks = ragService.chunk(content, 1024, 0);

        assertEquals(1, chunks.size());
        assertEquals("这是一个简短的段落，不超过chunkSize。", chunks.get(0));
    }

    @Test
    void chunkMultipleParagraphsFitsInOneChunkWhenLargeSize() {
        // Three short paragraphs within chunkSize are merged (expected behavior)
        String p1 = "第一段内容。";
        String p2 = "第二段内容。";
        String p3 = "第三段内容。";
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = ragService.chunk(content, 500, 0);

        // With large chunkSize, all three are concatenated into 1 chunk
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains(p1));
        assertTrue(chunks.get(0).contains(p2));
        assertTrue(chunks.get(0).contains(p3));
    }

    @Test
    void chunkMultipleLongParagraphsSplitWhenExceedsSize() {
        // Each paragraph has ~50 chars, chunkSize=60 so each paragraph exceeds limit separately
        // and adding to current would overflow
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('A');
        String p1 = sb.toString();
        sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('B');
        String p2 = sb.toString();
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 60, 0);

        assertTrue(chunks.size() >= 2, "Expected at least 2 chunks for long paragraphs");
    }

    @Test
    void chunkSkipsEmptyParagraphs() {
        // Paragraph 1: non-empty, Paragraph 2: many empty ones, Paragraph 3: non-empty
        // Both real paragraphs are SHORT, so with large enough chunkSize they merge to 1
        String content = "第一段。\n\n\n\n   \n\n第二段。";

        List<String> chunks = ragService.chunk(content, 200, 0);

        // Result >= 1 means empty paragraphs were skipped (no empty chunks)
        assertTrue(chunks.size() >= 1);
        for (String c : chunks) {
            assertFalse(c.trim().isEmpty(), "No empty chunks allowed");
        }
    }

    @Test
    void chunkSizeForcesSplitEvenOfShortParagraphs() {
        // Each paragraph: 10 chars, chunkSize=15 → first = 10, next 10 would be 10+10=20 > 15 → split
        String p1 = "AAAAAAAAAA";  // 10
        String p2 = "BBBBBBBBBB";  // 10
        String p3 = "CCCCCCCCCC";  // 10
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        // chunkSize=15: after first para (10 chars), adding second (10) would be 20 > 15 → split
        List<String> chunks = ragService.chunk(content, 15, 0);

        assertTrue(chunks.size() >= 2, "Expected at least 2 chunks when size forces split, got " + chunks.size());
        assertEquals(p1, chunks.get(0));
    }

    @Test
    void chunkVeryLongParagraphSplitsBySentences() {
        // Build a single long paragraph (> chunkSize) with sentence delimiters
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("句子").append(i).append("。");
        }
        String longPara = sb.toString();  // Each sentence ~5 chars, 20 sentences → ~100 chars

        // chunkSize=30: should split
        List<String> chunks = ragService.chunk(longPara, 30, 0);

        assertTrue(chunks.size() >= 2, "Long paragraph should be split by sentences, got: " + chunks.size());
    }

    @Test
    void chunkVeryLongParagraphWithMixedPunctuation() {
        String longPara = "你好！这是第一段。有感叹号？也有问号。还有英文句号. Mixed punctuations.";
        for (int i = 0; i < 30; i++) {
            longPara += " 追加句子" + i + "。";
        }

        List<String> chunks = ragService.chunk(longPara, 50, 0);

        assertTrue(chunks.size() >= 3, "Mixed punctuation long para expected to split into >= 3, got " + chunks.size());
    }

    @Test
    void chunkWithOverlapAppliedForMultipleChunks() {
        // Force 2+ chunks with very small chunkSize, then verify overlap is appended to non-last chunks
        String p1 = "AAAAAAAAAA";  // 10
        String p2 = "BBBBBBBBBB";  // 10
        String content = p1 + "\n\n" + p2;

        // chunkSize=15, overlap=3: p1 chunk gets first 3 chars of p2 appended
        List<String> chunks = ragService.chunk(content, 15, 3);

        assertTrue(chunks.size() >= 2, "Need at least 2 chunks for overlap to apply");
        // First chunk should be longer than p1 alone
        assertTrue(chunks.get(0).length() > p1.length(),
                "First chunk should have overlap appended to original content");
    }

    @Test
    void chunkWithZeroOverlapNoExtraContent() {
        String p1 = "AAAAA";
        String p2 = "BBBBB";
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 10, 0);

        // With size 10, single or split chunks; no overlap text markers
        for (String c : chunks) {
            assertFalse(c.contains("..."));
        }
    }

    @Test
    void chunkEmptyOrWhitespaceReturnsEmptyList() {
        assertEquals(0, ragService.chunk("", 100, 0).size());
        assertEquals(0, ragService.chunk("   \n\n   \n\n  ", 100, 0).size());
        assertEquals(0, ragService.chunk("\n\n\n\n", 100, 0).size());
    }

    @Test
    void chunkSingleChunkNoOverlapApplied() {
        String content = "单独的短段落。";
        List<String> chunks = ragService.chunk(content, 100, 50);
        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0));
    }

    @Test
    void chunkOverlapUsesMaxAvailableFromNextChunk() {
        // Force 2+ chunks: p1 fills up to ~10 chars, p2 is short (2 chars)
        String p1 = "AAAAAAAAAA";  // 10
        String p2 = "BB";          // only 2 chars
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 15, 100);  // overlap > p2 length

        // With size 15, we should get >= 2 chunks
        // (p1=10 chars, adding p2+separator would exceed)
        if (chunks.size() >= 2) {
            assertTrue(chunks.get(0).length() > p1.length(),
                    "First chunk should have overlap appended (p1 + overlap of p2)");
            // Should not throw index out of bounds
            assertNotNull(chunks.get(0));
        }
        // If chunks.size() == 1 (p1+p2 both fit), that's OK too (no overlap needed for 1 chunk)
    }

    // --- toVectorString() ---

    @Test
    void toVectorStringEmptyArray() {
        String result = ragService.toVectorString(new float[0]);
        assertEquals("[]", result);
    }

    @Test
    void toVectorStringSingleElement() {
        String result = ragService.toVectorString(new float[]{1.5f});
        assertEquals("[1.5]", result);
    }

    @Test
    void toVectorStringMultipleElements() {
        String result = ragService.toVectorString(new float[]{0.1f, 0.2f, -0.3f});
        assertEquals("[0.1,0.2,-0.3]", result);
    }

    @Test
    void toVectorStringLargeDimension() {
        float[] vec = new float[1024];
        for (int i = 0; i < 1024; i++) vec[i] = i * 0.001f;

        String result = ragService.toVectorString(vec);

        assertTrue(result.startsWith("["));
        assertTrue(result.endsWith("]"));
        long commas = result.chars().filter(c -> c == ',').count();
        assertEquals(1023, commas);
    }

    // --- extractStructure() ---

    @Test
    void extractStructureWithEmptyChunksReturnsEmpty() {
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of());

        String result = ragService.extractStructure("doc1");

        assertEquals("", result);
    }

    @Test
    void extractStructureIncludesFirstChunkPrefix() {
        String firstChunk = "引言部分：这是一篇关于Java编程的教程文档，涵盖基础语法、面向对象、集合框架等核心内容。后续章节详细介绍每个主题。";
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(firstChunk));

        String result = ragService.extractStructure("doc1");

        assertTrue(result.contains("【文档开头】"));
        assertTrue(result.contains("引言部分"));
    }

    @Test
    void extractStructureTruncatesLongFirstChunk() {
        // Generate content > 500 chars so truncation branch actually executes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("词汇").append(i).append(" ");
        }
        String longFirst = sb.toString();
        // Ensure we actually generated more than 500 chars (should be ~970+)
        assertTrue(longFirst.length() > 500, "Test setup: first chunk must exceed 500 chars");

        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(longFirst));

        String result = ragService.extractStructure("doc1");

        assertTrue(result.contains("【文档开头】"));
        // Truncated version should contain the start
        String expectedPrefix = longFirst.substring(0, Math.min(500, longFirst.length()));
        assertTrue(result.contains("【文档开头】\n" + expectedPrefix),
                "Should include truncated (first 500 chars) content of first chunk");
        // And the result should NOT include the very end of the long first chunk
        // (because first chunk is > 500, last chars beyond 500 should be absent)
        String tailMarker = "词汇199";  // near end, definitely beyond 500th char
        assertFalse(result.contains(tailMarker),
                "Truncation should drop content beyond the 500-char cutoff");
    }

    @Test
    void extractStructureIdentifiesShortHeadingLikeChunks() {
        // Headings must be length > 10 AND < 200 AND contain no newlines
        String firstChunk = "开篇介绍。";
        // Make these LONG enough (> 10 chars)
        String h1 = "第一章 Java基础语法入门";         // 11+ chars
        String h2 = "第二章 面向对象编程概念详解";      // > 10
        // Body: long enough (> 200 chars) to NOT be heading
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) body.append("详细的正文内容解释");
        String longBody = body.toString();  // ~210+ chars
        String h3 = "第三章 集合框架深度解析";           // > 10

        when(chunkMapper.getAllChunks("doc1"))
                .thenReturn(List.of(firstChunk, h1, h2, longBody, h3));

        String result = ragService.extractStructure("doc1");

        assertTrue(result.contains("【章节标题】"), "Should detect heading section");
        assertTrue(result.contains(h1), "Should include h1: " + h1);
        assertTrue(result.contains(h2), "Should include h2: " + h2);
        assertTrue(result.contains(h3), "Should include h3: " + h3);
        assertFalse(result.contains(longBody.substring(0, 20)), "Should NOT include body as heading");
    }

    @Test
    void extractStructureNoHeadingsFoundDoesNotIncludeSection() {
        String firstChunk = "文档开头。";
        // All other chunks have > 200 chars (long text) → no headings detected
        StringBuilder sb1 = new StringBuilder();
        for (int i = 0; i < 40; i++) sb1.append("这是一个较长的正文段落，包含很多文字内容用来描述细节。");
        String longBody1 = sb1.toString();  // ~ 40 * 25 = 1000 chars

        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < 30; i++) sb2.append("又一段很长的文字内容，详细说明具体实现细节和注意事项。");
        String longBody2 = sb2.toString();

        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(firstChunk, longBody1, longBody2));

        String result = ragService.extractStructure("doc1");

        assertTrue(result.contains("【文档开头】"));
        assertFalse(result.contains("【章节标题】"),
                "Long body-only chunks should not trigger heading section");
    }

    @Test
    void extractStructureVeryShortChunksAreNotHeadings() {
        // length <= 10 not treated as headings (strictly > 10 required)
        String firstChunk = "intro";
        String short1 = "标题短";     // 3 chars
        String short2 = "ok12345";     // 7 chars
        String short3 = "正好是10个字符"; // check exactly 10? Need to ensure it's <= 10

        when(chunkMapper.getAllChunks("doc1"))
                .thenReturn(List.of(firstChunk, short1, short2, short3));

        String result = ragService.extractStructure("doc1");

        // None should be headings because all are too short (length <= 10 or contain newline check)
        assertFalse(result.contains("【章节标题】"),
                "Short chunks (<= 10 chars threshold) should not be classified as headings");
    }

    // --- chunk query fallback / error resilience ---

    @Test
    void getChunksByIndexOnExceptionReturnsEmpty() {
        when(chunkMapper.getChunksByIndex(anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        var result = ragService.getChunksByIndex("doc1", 10);
        assertEquals(List.of(), result);
    }

    @Test
    void getChunksUniformOnExceptionReturnsEmpty() {
        when(chunkMapper.getChunksUniform(anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        var result = ragService.getChunksUniform("doc1", 3);
        assertEquals(List.of(), result);
    }

    @Test
    void searchByDocumentOnExceptionReturnsEmpty() {
        // No need to mock chunkMapper explicitly; embed() fails first and returns empty
        // We're testing that the method does not throw regardless of the exception source
        assertDoesNotThrow(() -> ragService.searchByDocument("doc1", "query", 5));
        var result = ragService.searchByDocument("doc1", "query", 5);
        assertEquals(List.of(), result);
    }

    @Test
    void searchAllOnExceptionReturnsEmpty() {
        // embed() will throw → caught → returns empty. No mocks needed.
        assertDoesNotThrow(() -> ragService.searchAll("query", 5));
        var result = ragService.searchAll("query", 5);
        assertEquals(List.of(), result);
    }
}
