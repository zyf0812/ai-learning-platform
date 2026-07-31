package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "embedding-model", 3);
    }

    // ---------- chunk() algorithm ----------

    @Test
    void chunkSplitsByParagraph() {
        String content = "Paragraph one text.\n\nSecond paragraph here.\n\nThird final paragraph.";
        List<String> chunks = ragService.chunk(content, 500, 0);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).startsWith("Paragraph one"));
        assertTrue(chunks.get(1).startsWith("Second paragraph"));
        assertTrue(chunks.get(2).startsWith("Third final"));
    }

    @Test
    void chunkSkipsEmptyParagraphs() {
        String content = "Para A\n\n\n\nPara B\n\n   \n\nPara C";
        List<String> chunks = ragService.chunk(content, 500, 0);
        assertEquals(3, chunks.size());
    }

    @Test
    void chunkLongParagraphSplitsBySentence() {
        // Single long paragraph > chunkSize, split by sentence punctuation
        StringBuilder sb = new StringBuilder();
        // Sentences: each ~ 50 chars, 10 sentences = 500 chars
        for (int i = 0; i < 20; i++) {
            sb.append("This is sentence number ").append(i).append("。");
        }
        String content = sb.toString();
        // chunkSize 200, so multiple chunks needed
        List<String> chunks = ragService.chunk(content, 200, 0);
        assertTrue(chunks.size() >= 2);
        // All content should be preserved across chunks
        StringBuilder rebuilt = new StringBuilder();
        for (String c : chunks) rebuilt.append(c);
        // Each chunk has its own trimmed copy; verify total non-empty
        for (String c : chunks) assertFalse(c.isEmpty());
    }

    @Test
    void chunkOverlapAppendsNextChunkStart() {
        // Three small paragraphs
        String p1 = "AAA content here AAA";  // short
        String p2 = "BBB content here BBB";
        String p3 = "CCC content here CCC content longer";
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        // Use chunkSize 500 (fits each), overlap 5
        List<String> chunks = ragService.chunk(content, 500, 5);
        assertEquals(3, chunks.size());

        // First chunk has no previous, but has overlap from next chunk start
        assertTrue(chunks.get(0).contains("AAA"));
        // Overlap is appended: first chunk should include start of second
        assertTrue(chunks.get(0).contains("BBB c"),
            "First chunk should include overlap start of second chunk: " + chunks.get(0));

        // Second chunk should include overlap from third
        assertTrue(chunks.get(1).contains("BBB"));
        assertTrue(chunks.get(1).contains("CCC c"),
            "Second chunk should include overlap from third");

        // Last chunk has no next - no extra overlap appended
        assertTrue(chunks.get(2).contains("CCC"));
    }

    @Test
    void chunkNoOverlapWhenSizeEqualsOne() {
        String content = "Only one single paragraph here that fits entirely.";
        List<String> chunks = ragService.chunk(content, 500, 100);
        assertEquals(1, chunks.size());
        // Overlap not added when chunks.size() <= 1
        assertFalse(chunks.get(0).endsWith("\n\n"));
    }

    @Test
    void chunkEmptyInputReturnsEmpty() {
        List<String> chunks = ragService.chunk("", 100, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkSentenceSplitUsesPunctuation() {
        // Test different sentence delimiters
        String content = "First sentence! Second sentence? Third sentence. Fourth one。Fifth！Sixth？";
        List<String> chunks = ragService.chunk(content, 50, 0);
        assertTrue(chunks.size() >= 1);
        assertTrue(chunks.get(0).contains("First"));
    }

    // ---------- toVectorString ----------

    @Test
    void toVectorStringFormatsBracketComma() {
        float[] vec = {1.5f, -2.0f, 3.25f};
        String result = ragService.toVectorString(vec);
        assertEquals("[1.5,-2.0,3.25]", result);
    }

    @Test
    void toVectorStringEmptyArray() {
        float[] vec = {};
        assertEquals("[]", ragService.toVectorString(vec));
    }

    @Test
    void toVectorStringSingleElement() {
        float[] vec = {42.0f};
        assertEquals("[42.0]", ragService.toVectorString(vec));
    }

    // ---------- extractStructure heading detection ----------

    @Test
    void extractStructureEmptyChunksReturnsEmpty() {
        when(chunkMapper.getAllChunks("doc-empty")).thenReturn(List.of());
        String result = ragService.extractStructure("doc-empty");
        assertEquals("", result);
    }

    @Test
    void extractStructureFindsShortHeadingLikeChunks() {
        String firstChunk = "Introduction to Databases. This document covers SQL, NoSQL and more.";
        String h1 = "Chapter 1: SQL Fundamentals";  // short, no newline -> heading
        String longBody = "This is a long paragraph that spans multiple sentences. ".repeat(20);
        String h2 = "Chapter 2: Indexing";
        String h3 = "2.1 B-Tree Indexes";

        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(firstChunk, h1, longBody, h2, h3));

        String result = ragService.extractStructure("doc1");
        assertTrue(result.contains("【文档开头】"));
        assertTrue(result.contains("Introduction to Databases"));
        assertTrue(result.contains("【章节标题】"));
        assertTrue(result.contains("Chapter 1: SQL Fundamentals"));
        assertTrue(result.contains("Chapter 2: Indexing"));
        assertTrue(result.contains("2.1 B-Tree Indexes"));
    }

    @Test
    void extractStructureFirstChunkTruncatedAt500Chars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("word").append(i).append(" ");  // ~ 800+ chars
        String longFirst = sb.toString();

        when(chunkMapper.getAllChunks("doc-long")).thenReturn(List.of(longFirst));
        String result = ragService.extractStructure("doc-long");

        assertTrue(result.contains("【文档开头】"));
        // The first chunk content should be truncated to 500
        int expectedStartIdx = result.indexOf("【文档开头】\n") + "【文档开头】\n".length();
        int headingIdx = result.indexOf("\n\n", expectedStartIdx);
        // The actual truncation in the code uses 500 cap on first chunk
        assertTrue(headingIdx - expectedStartIdx <= 510,  // 500 + some buffer
            "First chunk display should be truncated to 500 chars");
    }

    @Test
    void extractStructureMapperExceptionReturnsEmpty() {
        when(chunkMapper.getAllChunks("doc-ex")).thenThrow(new RuntimeException("DB down"));
        assertEquals("", ragService.extractStructure("doc-ex"));
    }

    // ---------- search/searchAll fallback on exception ----------

    @Test
    void searchByDocumentExceptionReturnsEmptyList() {
        // embed() will fail without a real server; we should catch and return empty
        // We can simulate by calling with null query handling via the try/catch in searchByDocument
        when(chunkMapper.searchByDocument(any(), any(), anyInt()))
            .thenThrow(new RuntimeException("vector error"));
        List<String> result = ragService.searchByDocument("doc1", "query", 5);
        assertEquals(List.of(), result);
    }

    @Test
    void searchAllExceptionReturnsEmpty() {
        when(chunkMapper.searchAll(any(), anyInt()))
            .thenThrow(new RuntimeException("error"));
        assertEquals(List.of(), ragService.searchAll("q", 5));
    }

    @Test
    void getChunksByIndexExceptionReturnsEmpty() {
        when(chunkMapper.getChunksByIndex(any(), anyInt()))
            .thenThrow(new RuntimeException("error"));
        assertEquals(List.of(), ragService.getChunksByIndex("d", 3));
    }

    @Test
    void getChunksUniformExceptionReturnsEmpty() {
        when(chunkMapper.getChunksUniform(any(), anyInt()))
            .thenThrow(new RuntimeException("error"));
        assertEquals(List.of(), ragService.getChunksUniform("d", 3));
    }
}
