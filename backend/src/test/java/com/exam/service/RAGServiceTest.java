package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RAG 切片算法决定检索质量：段落打包、超长段落按句切分、块间 overlap、以及文档结构提取。
 * 这些是纯逻辑，之前完全没有覆盖。
 */
class RAGServiceTest {

    private final DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
    private final RAGService rag = new RAGService(chunkMapper, "key", "http://localhost", "model", 1024);

    @Test
    void shortContentYieldsSingleChunk() {
        assertEquals(List.of("短文本"), rag.chunk("短文本", 100, 0));
    }

    @Test
    void paragraphsArePackedUntilSizeLimit() {
        List<String> chunks = rag.chunk("12345678\n\n12345678", 10, 0);
        assertEquals(List.of("12345678", "12345678"), chunks);
    }

    @Test
    void oversizedParagraphIsSplitAtSentenceBoundaries() {
        List<String> chunks = rag.chunk("abcde.fghij.", 5, 0);
        assertEquals(List.of("abcde.", "fghij."), chunks);
    }

    @Test
    void chineseSentencesAreSplitAtPunctuation() {
        List<String> chunks = rag.chunk("第一句。第二句。第三句。", 4, 0);
        assertEquals(List.of("第一句。", "第二句。", "第三句。"), chunks);
    }

    @Test
    void overlapIsAppendedToAllButLastChunk() {
        List<String> chunks = rag.chunk("AAA\n\nBBB\n\nCCC", 3, 2);

        assertEquals(3, chunks.size());
        assertEquals("AAA\n\nBB", chunks.get(0));
        assertEquals("BBB\n\nCC", chunks.get(1));
        assertEquals("CCC", chunks.get(2), "最后一块不应追加 overlap");
    }

    @Test
    void overlapIsTruncatedWhenNextChunkIsShorter() {
        List<String> chunks = rag.chunk("AAA\n\nB", 3, 10);

        assertEquals(2, chunks.size());
        assertEquals("AAA\n\nB", chunks.get(0));
        assertEquals("B", chunks.get(1));
    }

    @Test
    void singleChunkIsNotOverlapped() {
        assertEquals(List.of("只有一个块"), rag.chunk("只有一个块", 100, 10));
    }

    @Test
    void blankParagraphsAreIgnored() {
        assertTrue(rag.chunk("\n\n\n  \n\n", 100, 0).isEmpty());
        assertEquals(List.of("A", "B"), rag.chunk("A\n\n\n\nB", 3, 0));
    }

    @Test
    void toVectorStringUsesBracketLiteralFormat() {
        assertEquals("[1.5,-2.0]", rag.toVectorString(new float[]{1.5f, -2.0f}));
        assertEquals("[]", rag.toVectorString(new float[0]));
    }

    @Test
    void extractStructureIncludesOpeningAndHeadings() {
        String first = "开".repeat(600);
        String heading = "第一章 面向对象编程基础";
        when(chunkMapper.getAllChunks("d1")).thenReturn(List.of(first, heading, "短块", "带\n换行的块"));

        String structure = rag.extractStructure("d1");

        assertTrue(structure.contains("【文档开头】"));
        assertTrue(structure.contains("开".repeat(500)));
        assertFalse(structure.contains("开".repeat(501)), "开头部分应截断到 500 字符");
        assertTrue(structure.contains("【章节标题】"));
        assertTrue(structure.contains("· " + heading));
        assertFalse(structure.contains("· 短块"), "少于10字符的块不应被当作标题");
        assertFalse(structure.contains("· 带"), "包含换行的块不应被当作标题");
    }

    @Test
    void extractStructureHandlesEmptyAndFailure() {
        when(chunkMapper.getAllChunks("empty")).thenReturn(List.of());
        assertEquals("", rag.extractStructure("empty"));

        when(chunkMapper.getAllChunks("broken")).thenThrow(new RuntimeException("db down"));
        assertEquals("", rag.extractStructure("broken"), "结构提取失败应降级为空串而不是中断问答");
    }
}
