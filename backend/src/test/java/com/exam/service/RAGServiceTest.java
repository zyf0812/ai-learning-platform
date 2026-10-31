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
 * RAG 文本分块与文档结构提取是检索、出题、知识提取的共同输入。
 * 分块边界（长段落、overlap、空内容）此前无覆盖。
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "key", "https://example.com", "embedding-3", 4);
    }

    @Test
    void chunkSplitsByParagraphsAndRespectsChunkSize() {
        List<String> chunks = ragService.chunk("aaa\n\nbbb\n\nccc", 10, 0);

        assertEquals(2, chunks.size());
        assertEquals("aaa\n\nbbb", chunks.get(0));
        assertEquals("ccc", chunks.get(1));
    }

    @Test
    void chunkAddsOverlapFromNextChunk() {
        List<String> chunks = ragService.chunk("aaa\n\nbbb\n\nccc", 10, 2);

        assertEquals(2, chunks.size());
        assertEquals("aaa\n\nbbb\n\ncc", chunks.get(0));
        assertEquals("ccc", chunks.get(1));
    }

    @Test
    void chunkSplitsOversizedParagraphAtSentenceBoundaries() {
        List<String> chunks = ragService.chunk("句子一。句子二。句子三。", 8, 0);

        assertEquals(2, chunks.size());
        assertEquals("句子一。句子二。", chunks.get(0));
        assertEquals("句子三。", chunks.get(1));
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 8));
    }

    @Test
    void chunkEmptyContentReturnsEmptyList() {
        assertTrue(ragService.chunk("  \n\n  \n\n", 10, 2).isEmpty());
        assertTrue(ragService.chunk("", 10, 2).isEmpty());
    }

    @Test
    void chunkDoesNotApplyOverlapWhenOnlyOneChunk() {
        List<String> chunks = ragService.chunk("只有一段内容", 100, 5);

        assertEquals(List.of("只有一段内容"), chunks);
    }

    @Test
    void toVectorStringFormatsEmbedding() {
        assertEquals("[1.0,2.5]", ragService.toVectorString(new float[]{1.0f, 2.5f}));
    }

    @Test
    void extractStructureIncludesOpeningAndHeadings() {
        when(chunkMapper.getAllChunks("doc-1")).thenReturn(List.of(
            "这是文档的开头前言部分。",
            "第一章 绪论与背景介绍",
            "这是正文段落\n包含换行内容",
            "第二章 方法与实现细节"));

        String structure = ragService.extractStructure("doc-1");

        assertTrue(structure.contains("【文档开头】"));
        assertTrue(structure.contains("这是文档的开头前言部分。"));
        assertTrue(structure.contains("【章节标题】"));
        assertTrue(structure.contains("第一章 绪论与背景介绍"));
        assertTrue(structure.contains("第二章 方法与实现细节"));
        assertFalse(structure.contains("包含换行内容"));
    }

    @Test
    void extractStructureReturnsEmptyWhenNoChunks() {
        when(chunkMapper.getAllChunks("doc-1")).thenReturn(List.of());

        assertEquals("", ragService.extractStructure("doc-1"));
    }

    @Test
    void extractStructureReturnsEmptyWhenStorageFails() {
        when(chunkMapper.getAllChunks("doc-1")).thenThrow(new RuntimeException("db down"));

        assertEquals("", ragService.extractStructure("doc-1"));
    }
}
