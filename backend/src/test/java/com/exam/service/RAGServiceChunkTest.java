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
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "embedding-3", 8);
    }

    @Test
    void chunkSplitsParagraphsWithoutOverlap() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb\n\ncccc", 4, 0);

        assertEquals(List.of("aaaa", "bbbb", "cccc"), chunks);
    }

    @Test
    void chunkAppendsOverlapFromNextChunkExceptForLast() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb\n\ncccc", 4, 2);

        assertEquals(3, chunks.size());
        assertEquals("aaaa\n\nbb", chunks.get(0));
        assertEquals("bbbb\n\ncc", chunks.get(1));
        assertEquals("cccc", chunks.get(2));
    }

    @Test
    void chunkSplitsOverlongParagraphBySentence() {
        List<String> chunks = ragService.chunk("第一句。第二句。第三句。", 6, 0);

        assertEquals(List.of("第一句。", "第二句。", "第三句。"), chunks);
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 6));
    }

    @Test
    void chunkReturnsEmptyListForBlankContent() {
        assertTrue(ragService.chunk("", 1024, 0).isEmpty());
        assertTrue(ragService.chunk("  \n\n \n\n", 1024, 0).isEmpty());
    }

    @Test
    void toVectorStringProducesPostgresVectorLiteral() {
        assertEquals("[0.5,-1.25,2.0]", ragService.toVectorString(new float[] {0.5f, -1.25f, 2.0f}));
    }
}
