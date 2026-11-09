package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RAGServiceTest {

    private final RAGService ragService = new RAGService(
            mock(DocumentChunkMapper.class), "test-key", "http://localhost:1", "embedding-3", 8);

    @Test
    void emptyContentProducesNoChunks() {
        assertTrue(ragService.chunk("", 1024, 0).isEmpty());
        assertTrue(ragService.chunk("\n\n\n", 1024, 0).isEmpty());
    }

    @Test
    void shortParagraphIsKeptAsSingleChunk() {
        List<String> chunks = ragService.chunk("hello", 1024, 0);
        assertEquals(List.of("hello"), chunks);
    }

    @Test
    void paragraphsAreMergedUntilChunkSizeIsExceeded() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb", 8, 0);

        assertEquals(List.of("aaaa", "bbbb"), chunks);
    }

    @Test
    void adjacentChunksReceiveOverlapFromNextChunk() {
        List<String> chunks = ragService.chunk("aaaa\n\nbbbb", 8, 2);

        assertEquals(2, chunks.size());
        assertEquals("aaaa\n\nbb", chunks.get(0));
        assertEquals("bbbb", chunks.get(1));
    }

    @Test
    void longParagraphIsSplitBySentenceAndContentIsPreserved() {
        String content = "aaaa。bbbb。cccc。\n\nzz";

        List<String> chunks = ragService.chunk(content, 7, 0);

        assertEquals(3, chunks.size());
        assertEquals("aaaa。", chunks.get(0));
        assertEquals("bbbb。", chunks.get(1));
        assertEquals("cccc。zz", chunks.get(2));
        String joined = String.join("", chunks);
        assertTrue(joined.contains("aaaa。") && joined.contains("bbbb。")
                && joined.contains("cccc。") && joined.contains("zz"));
    }

    @Test
    void overlappedChunksNeverReadPastNextChunkBoundary() {
        List<String> chunks = ragService.chunk("aaa\n\nb", 4, 100);

        assertEquals(2, chunks.size());
        assertEquals("aaa\n\nb", chunks.get(0));
        assertEquals("b", chunks.get(1));
    }
}
