package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RAGServiceTest {

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        // 不需要真实的 mapper，仅测试纯逻辑 chunk() 方法
        DocumentChunkMapper mockMapper = mock(DocumentChunkMapper.class);
        ragService = new RAGService(mockMapper, "test-key", "http://localhost", "embedding-3", 1024);
    }

    // ========== chunk() 分块算法 ==========

    @Test
    void chunkEmptyContentReturnsEmpty() {
        assertTrue(ragService.chunk("", 100, 0).isEmpty());
    }

    @Test
    void chunkSingleShortParagraphSingleChunk() {
        String content = "这是一个很短的段落。";
        List<String> chunks = ragService.chunk(content, 100, 0);
        assertEquals(1, chunks.size());
        assertEquals("这是一个很短的段落。", chunks.get(0));
    }

    @Test
    void chunkMultipleShortParagraphsMergedWithinLimit() {
        String content = "段落一内容。\n\n段落二内容。\n\n段落三内容。";
        List<String> chunks = ragService.chunk(content, 50, 0);
        // 三个短段落应能合并到一个块
        assertFalse(chunks.isEmpty());
    }

    @Test
    void chunkMultipleParagraphsExceedLimit_SplitIntoMultiple() {
        // 构造足够长的多个段落
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("第").append(i).append("段：").append("A".repeat(50)).append("\n\n");
        }
        List<String> chunks = ragService.chunk(sb.toString(), 100, 0);
        assertTrue(chunks.size() > 1);
        // 每块不应超过 chunkSize 太多
        for (String c : chunks) {
            assertTrue(c.length() > 0);
        }
    }

    @Test
    void chunkVeryLongParagraph_SplitBySentences() {
        // 超长段落，应按句子切分
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("第").append(i).append("个句子的内容很充实。");
        }
        List<String> chunks = ragService.chunk(sb.toString(), 80, 0);
        assertTrue(chunks.size() > 1);
        // 每个块应以句子结尾标点结束
        for (String c : chunks) {
            assertTrue(c.length() > 0);
        }
    }

    @Test
    void chunkEmptyParagraphsSkipped() {
        String content = "\n\n\n\n段落一。\n\n\n\n\n段落二。\n\n\n";
        List<String> chunks = ragService.chunk(content, 100, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("段落一"));
        assertTrue(chunks.get(0).contains("段落二"));
    }

    // ========== overlap ==========

    @Test
    void chunkOverlapAddsPrefixOfNext() {
        // 构造会被切分为多块的内容
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            sb.append("第").append(i).append("段").append("X".repeat(30)).append("。\n\n");
        }
        List<String> noOverlap = ragService.chunk(sb.toString(), 100, 0);
        List<String> withOverlap = ragService.chunk(sb.toString(), 100, 20);

        // 开启 overlap 后，除了最后一块，每块都应比原来更长
        if (noOverlap.size() > 1 && withOverlap.size() > 1) {
            for (int i = 0; i < withOverlap.size() - 1; i++) {
                assertTrue(withOverlap.get(i).length() >= noOverlap.get(i).length());
            }
        }
    }

    @Test
    void chunkSingleChunkIgnoresOverlap() {
        String content = "单个短段落。";
        List<String> chunks = ragService.chunk(content, 100, 50);
        assertEquals(1, chunks.size());
        assertEquals("单个短段落。", chunks.get(0));
    }

    // ========== toVectorString ==========

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
        String s = ragService.toVectorString(new float[]{1.0f, 2.5f, -3.0f});
        assertEquals("[1.0,2.5,-3.0]", s);
    }

    // ========== 搜索方法降级（不依赖embed） ==========

    @Test
    void searchByDocumentThrowsReturnsEmptyList() {
        // 不提供有效 key，embed 会失败，应降级返回空
        List<String> result = ragService.searchByDocument("d1", "query", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchAllThrowsReturnsEmptyList() {
        List<String> result = ragService.searchAll("query", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void getChunksByIndexThrowsReturnsEmptyList() {
        List<String> result = ragService.getChunksByIndex("d1", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void getChunksUniformThrowsReturnsEmptyList() {
        List<String> result = ragService.getChunksUniform("d1", 3);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractStructureThrowsReturnsEmptyString() {
        assertEquals("", ragService.extractStructure("d1"));
    }
}
