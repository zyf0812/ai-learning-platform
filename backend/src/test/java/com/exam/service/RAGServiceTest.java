package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * RAGService 的分块与降级逻辑直接决定检索质量和上下文注入效果，
 * 但原测试完全没有覆盖。测试不发起任何网络请求。
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "test-model", 8);
    }

    @Test
    void emptyContentProducesNoChunks() {
        assertTrue(ragService.chunk("", 1024, 0).isEmpty());
        assertTrue(ragService.chunk("   \n\n  ", 1024, 0).isEmpty());
    }

    @Test
    void shortContentProducesSingleChunk() {
        assertEquals(List.of("hello"), ragService.chunk("hello", 100, 0));
    }

    @Test
    void paragraphsArePackedWithinChunkSize() {
        List<String> chunks = ragService.chunk(
            "1234567890\n\nabcdefghij\n\nKLMNOPQRST", 20, 0);

        assertEquals(List.of("1234567890", "abcdefghij", "KLMNOPQRST"), chunks);
    }

    @Test
    void longParagraphIsSplitOnSentenceBoundaries() {
        List<String> chunks = ragService.chunk("abcde。fghij。klmno。", 10, 0);

        assertEquals(List.of("abcde。", "fghij。", "klmno。"), chunks);
    }

    @Test
    void overlapAppendsPrefixOfNextChunkButNotLastChunk() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb", 5, 2);

        assertEquals(2, chunks.size());
        assertEquals("aaaa\n\nbb", chunks.get(0));
        assertEquals("bbbb", chunks.get(1));
    }

    @Test
    void overlapIsIgnoredForSingleChunk() {
        assertEquals(List.of("hello"), ragService.chunk("hello", 100, 3));
    }

    @Test
    void toVectorStringFormatsValuesForPgVector() {
        assertEquals("[1.5,-2.0]", ragService.toVectorString(new float[]{1.5f, -2.0f}));
    }

    @Test
    void chunkLookupsDegradeToEmptyListWhenDatabaseFails() {
        when(chunkMapper.getChunksByIndex("doc1", 3)).thenThrow(new RuntimeException("db down"));
        when(chunkMapper.getChunksUniform("doc1", 3)).thenThrow(new RuntimeException("db down"));

        assertTrue(ragService.getChunksByIndex("doc1", 3).isEmpty());
        assertTrue(ragService.getChunksUniform("doc1", 3).isEmpty());
    }

    @Test
    void extractStructureIncludesOpeningAndHeadings() {
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(
            "第一段开头内容\n第二行内容",
            "第三章 并发编程基础详解",
            "x"
        ));

        String structure = ragService.extractStructure("doc1");

        assertTrue(structure.contains("【文档开头】"));
        assertTrue(structure.contains("第一段开头内容"));
        assertTrue(structure.contains("【章节标题】"));
        assertTrue(structure.contains("第三章 并发编程基础详解"));
        assertFalse(structure.contains("· x"));
    }

    @Test
    void extractStructureReturnsEmptyStringWhenDatabaseFails() {
        when(chunkMapper.getAllChunks("doc1")).thenThrow(new RuntimeException("db down"));

        assertEquals("", ragService.extractStructure("doc1"));
    }
}
