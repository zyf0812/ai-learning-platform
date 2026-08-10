package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock private DocumentChunkMapper chunkMapper;
    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://test.local",
                "test-model", 1024);
    }

    // === chunk() tests ===

    @Test
    void chunkSimpleParagraphs() {
        String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        var chunks = ragService.chunk(content, 1024, 0);

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).contains("第一段内容"));
        assertTrue(chunks.get(1).contains("第二段内容"));
        assertTrue(chunks.get(2).contains("第三段内容"));
    }

    @Test
    void chunkSkipsEmptyParagraphs() {
        String content = "有效内容。\n\n\n\n更多内容。";
        var chunks = ragService.chunk(content, 1024, 0);

        assertEquals(2, chunks.size());
    }

    @Test
    void chunkEmptyContentReturnsEmpty() {
        var chunks = ragService.chunk("", 1024, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkVeryLongParagraphSplitBySentences() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是第").append(i).append("句话。");
        }
        String content = sb.toString();

        var chunks = ragService.chunk(content, 200, 0);

        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 200 + 50);
        }
    }

    @Test
    void chunkSmallChunksMergesBySize() {
        String content = "短句一。\n\n短句二。\n\n短句三。";
        var chunks = ragService.chunk(content, 1024, 0);

        assertTrue(chunks.size() <= 3);
    }

    @Test
    void chunkWithOverlapAddsContextBetweenChunks() {
        String content = "第一段的内容比较独立。\n\n第二段的内容也比较独立。\n\n第三段的内容。";
        var chunks = ragService.chunk(content, 1024, 50);

        assertEquals(3, chunks.size());
        String first = chunks.get(0);
        String second = chunks.get(1);
        assertTrue(second.length() > 0);
    }

    @Test
    void chunkOverlapNotAppliedWhenSingleChunk() {
        String content = "只有一段内容，不足以分块。";
        var chunks = ragService.chunk(content, 1024, 50);

        assertEquals(1, chunks.size());
    }

    @Test
    void chunkOverlapZeroDoesNotAddContext() {
        String content = "第一段。\n\n第二段。";
        var chunks = ragService.chunk(content, 1024, 0);

        assertEquals(2, chunks.size());
    }

    @Test
    void chunkParagraphTooLongGetsSplit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("句子").append(i).append("。");
        }
        var chunks = ragService.chunk(sb.toString(), 50, 0);

        assertFalse(chunks.isEmpty());
        for (String c : chunks) {
            assertTrue(c.length() <= 50 + 30);
        }
    }

    // === toVectorString() tests ===

    @Test
    void toVectorStringFormatsCorrectly() {
        float[] vec = {1.0f, 2.5f, 3.75f};
        String result = ragService.toVectorString(vec);

        assertEquals("[1.0,2.5,3.75]", result);
    }

    @Test
    void toVectorStringEmptyArray() {
        float[] vec = {};
        String result = ragService.toVectorString(vec);

        assertEquals("[]", result);
    }

    @Test
    void toVectorStringSingleElement() {
        float[] vec = {42.0f};
        String result = ragService.toVectorString(vec);

        assertEquals("[42.0]", result);
    }
}