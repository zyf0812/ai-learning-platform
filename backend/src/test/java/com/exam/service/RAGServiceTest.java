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

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost:1",
                "test-model", 8);
    }

    @Test
    void chunkReturnsEmptyListForBlankContent() {
        assertTrue(ragService.chunk("", 1024, 0).isEmpty());
        assertTrue(ragService.chunk("  \n\n  \n\n", 1024, 0).isEmpty());
    }

    @Test
    void chunkKeepsShortParagraphsTogether() {
        List<String> chunks = ragService.chunk("第一段\n\n第二段", 100, 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段"));
    }

    @Test
    void chunkSplitsWhenParagraphsExceedChunkSize() {
        String paraA = "a".repeat(50);
        String paraB = "b".repeat(50);

        List<String> chunks = ragService.chunk(paraA + "\n\n" + paraB, 60, 0);

        assertEquals(2, chunks.size());
        assertEquals(paraA, chunks.get(0));
        assertEquals(paraB, chunks.get(1));
    }

    @Test
    void chunkSplitsOversizedParagraphBySentence() {
        List<String> chunks = ragService.chunk("第一句。第二句。第三句。", 5, 0);

        assertEquals(3, chunks.size());
        assertEquals("第一句。", chunks.get(0));
        assertEquals("第二句。", chunks.get(1));
        assertEquals("第三句。", chunks.get(2));
    }

    @Test
    void chunkAppendsOverlapFromNextChunk() {
        String paraA = "a".repeat(40);
        String paraB = "b".repeat(40);
        String paraC = "c".repeat(40);

        List<String> chunks = ragService.chunk(paraA + "\n\n" + paraB + "\n\n" + paraC, 60, 5);

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).endsWith("\n\nbbbbb"));
        assertTrue(chunks.get(1).endsWith("\n\nccccc"));
        assertEquals(paraC, chunks.get(2));
    }

    @Test
    void toVectorStringFormatsValuesWithoutSpaces() {
        assertEquals("[1.5,-2.0]", ragService.toVectorString(new float[]{1.5f, -2.0f}));
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }

    @Test
    void extractStructureIncludesTruncatedHeadAndHeadings() {
        String head = "A".repeat(600);
        List<String> chunks = List.of(
                head,
                "第一章 计算机系统概述",
                "第一章\n正文内容",
                "第二章 数据结构与算法");
        when(chunkMapper.getAllChunks("doc1")).thenReturn(chunks);

        String structure = ragService.extractStructure("doc1");

        assertTrue(structure.contains("【文档开头】"));
        assertTrue(structure.contains("A".repeat(500)));
        assertFalse(structure.contains("A".repeat(501)));
        assertTrue(structure.contains("【章节标题】"));
        assertTrue(structure.contains("· 第一章 计算机系统概述"));
        assertTrue(structure.contains("· 第二章 数据结构与算法"));
        assertFalse(structure.contains("· 第一章\n正文内容"));
    }

    @Test
    void extractStructureReturnsEmptyWhenNoChunks() {
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of());

        assertEquals("", ragService.extractStructure("doc1"));
    }

    @Test
    void extractStructureSwallowsMapperFailure() {
        when(chunkMapper.getAllChunks("doc1")).thenThrow(new RuntimeException("db down"));

        assertEquals("", ragService.extractStructure("doc1"));
    }
}
