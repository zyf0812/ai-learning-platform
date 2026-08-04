package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAGService 中的 chunk() 文档分块算法是核心纯逻辑。
 * 覆盖：段落切分、超长段落按句子切分、overlap 机制。
 * 由于 chunk() 是 public，直接实例化即可调用。
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceChunkTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "fake-key", "http://fake", "emb-2", 1024);
    }

    // ===== chunk: 简单段落切分 =====

    @Test
    void chunkSplitsByDoubleNewline() {
        // Each paragraph longer than chunkSize/2 so merging exceeds limit -> separate chunks
        String content = "段落一第一行段落一第一行段落一第一行。\n\n" +
                         "段落二独立段落二独立段落二独立。\n\n" +
                         "段落三段落三段落三段落三段落三段落三。";
        // chunkSize=25, each para ~30 chars => overflow when trying to merge => 3 separate chunks
        List<String> chunks = ragService.chunk(content, 25, 0);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).contains("段落一"));
        assertTrue(chunks.get(1).contains("段落二"));
        assertTrue(chunks.get(2).contains("段落三"));
    }

    @Test
    void chunkSkipsEmptyParagraphs() {
        String content = "\n\n\n\n" +
                         "第一段第一段第一段第一段第一段。\n\n\n\n\n" +
                         "第二段第二段第二段第二段第二段。\n\n\n";
        List<String> chunks = ragService.chunk(content, 25, 0);
        assertEquals(2, chunks.size());
    }

    // ===== chunk: 单个短段落 single chunk =====

    @Test
    void chunkSingleShortParagraph() {
        String content = "只有一个短段落。";
        List<String> chunks = ragService.chunk(content, 500, 0);
        assertEquals(1, chunks.size());
        assertEquals("只有一个短段落。", chunks.get(0));
    }

    // ===== chunk: 空内容 =====

    @Test
    void chunkEmptyContentReturnsEmpty() {
        assertEquals(List.of(), ragService.chunk("", 100, 0));
        assertEquals(List.of(), ragService.chunk("\n\n\n", 100, 0));
    }

    // ===== chunk: 超长段落按句子切分 =====

    @Test
    void chunkLongParagraphSplitsBySentences() {
        // Build a paragraph well over chunkSize=30, with multiple sentences
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("第").append(i).append("句内容。");
        }
        String content = sb.toString();

        List<String> chunks = ragService.chunk(content, 30, 0);

        assertTrue(chunks.size() > 1, "Long paragraph should produce multiple chunks");
        // Each chunk should be at most ~chunkSize + a bit (one sentence)
        for (String c : chunks) {
            assertTrue(c.length() <= 60, "Chunk too large: " + c.length());
        }
    }

    @Test
    void chunkLongParagraphChineseSentences() {
        // Chinese punctuation 。！？ as sentence boundaries
        StringBuilder sb = new StringBuilder();
        sb.append("这是一段非常长的中文段落。");
        for (int i = 0; i < 20; i++) {
            sb.append("第").append(i).append("小段文本！");
        }
        sb.append("结束。");
        List<String> chunks = ragService.chunk(sb.toString(), 40, 0);
        assertTrue(chunks.size() > 1);
    }

    @Test
    void chunkLongParagraphEnglishSentences() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }
        List<String> chunks = ragService.chunk(sb.toString(), 60, 0);
        assertTrue(chunks.size() > 1, "Expected multiple chunks for long English text");
    }

    // ===== chunk: 段落合并至 chunkSize 上限 =====

    @Test
    void chunkMergesSmallParagraphs() {
        // Many tiny paragraphs — should be grouped together until chunkSize ~ 100 reached
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("小段。\n\n");
        }
        List<String> chunks = ragService.chunk(sb.toString(), 80, 0);
        assertTrue(chunks.size() < 15, "Small paragraphs should be merged, got " + chunks.size());
    }

    // ===== chunk: overlap 机制 =====

    @Test
    void chunkOverlapAppendsTailOfNextChunk() {
        String p1 = "A".repeat(100);
        String p2 = "B".repeat(100);
        String p3 = "C".repeat(100);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        // chunkSize=200 will keep each para separate as they overflow on merge
        // Better: use chunkSize larger than each para but smaller than two combined,
        // and chunkSize=200, overlap=20
        List<String> chunks = ragService.chunk(content, 150, 15);
        assertTrue(chunks.size() >= 2, "Need at least 2 chunks to test overlap");

        if (chunks.size() >= 2) {
            String first = chunks.get(0);
            // First chunk should contain some chars from the start of second chunk (overlap)
            String secondContent = p2;
            // overlap=15 means first chunk ends with first 15 chars of p2
            assertTrue(first.endsWith(secondContent.substring(0, 15)),
                "First chunk should carry overlap from next chunk start");
        }
    }

    @Test
    void chunkOverlapZeroDoesNotModifyChunks() {
        String p1 = "第一段落内容。";
        String p2 = "第二段落内容。";
        String content = p1 + "\n\n" + p2;
        List<String> chunks = ragService.chunk(content, 200, 0);
        assertEquals(1, chunks.size(), "Both small paragraphs merged");
    }

    @Test
    void chunkSingleChunkWithOverlapHasNoTrailingOverlap() {
        // Only one chunk — overlap mechanism skipped entirely (size>1 required)
        String content = "独立单段无后续。";
        List<String> chunks = ragService.chunk(content, 200, 50);
        assertEquals(1, chunks.size());
        assertEquals("独立单段无后续。", chunks.get(0));
    }

    // ===== toVectorString =====

    @Test
    void toVectorStringFormat() {
        float[] vec = {1.5f, -2.25f, 0.0f};
        String s = ragService.toVectorString(vec);
        assertEquals("[1.5,-2.25,0.0]", s);
    }

    @Test
    void toVectorStringEmpty() {
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }
}
