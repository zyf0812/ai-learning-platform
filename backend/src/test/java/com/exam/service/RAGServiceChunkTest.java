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
class RAGServiceChunkTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "key", "http://localhost", "embedding", 8);
    }

    @Test
    void chunkMergesShortParagraphsUpToChunkSize() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb\n\ncccc", 10, 0);

        assertEquals(List.of("aaaa\n\nbbbb", "cccc"), chunks);
    }

    @Test
    void chunkSplitsOversizedParagraphBySentenceBoundary() {
        List<String> chunks = ragService.chunk("第一句。第二句。第三句。", 10, 0);

        assertEquals(2, chunks.size());
        assertEquals("第一句。第二句。", chunks.get(0));
        assertEquals("第三句。", chunks.get(1));
    }

    @Test
    void chunkAppendsOverlapFromFollowingChunk() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb\n\ncccc", 10, 3);

        assertEquals(2, chunks.size());
        assertEquals("aaaa\n\nbbbb\n\nccc", chunks.get(0));
        assertEquals("cccc", chunks.get(1));
    }

    @Test
    void chunkDoesNotApplyOverlapToSingleChunk() {
        List<String> chunks = ragService.chunk("只有一段", 100, 50);

        assertEquals(List.of("只有一段"), chunks);
    }

    @Test
    void chunkIgnoresBlankParagraphs() {
        List<String> chunks = ragService.chunk("  \n\n\n\n内容", 100, 0);

        assertEquals(List.of("内容"), chunks);
    }

    @Test
    void chunkReturnsEmptyListForBlankContent() {
        assertTrue(ragService.chunk("   \n\n  ", 100, 0).isEmpty());
    }

    @Test
    void toVectorStringFormatsFloatsForPgVector() {
        assertEquals("[1.5,-2.0,0.0]", ragService.toVectorString(new float[]{1.5f, -2.0f, 0.0f}));
    }
}
