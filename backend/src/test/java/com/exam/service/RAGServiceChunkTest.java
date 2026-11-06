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
        ragService = new RAGService(chunkMapper, "key", "https://example.com", "model", 1024);
    }

    @Test
    void splitsParagraphsAtChunkSizeWithoutOverlap() {
        List<String> chunks = ragService.chunk("aaa\n\nbbb\n\nccc", 3, 0);

        assertEquals(List.of("aaa", "bbb", "ccc"), chunks);
    }

    @Test
    void splitsOversizedParagraphAtSentenceBoundaries() {
        String text = "第一句。第二句。第三句。";

        List<String> chunks = ragService.chunk(text, 4, 0);

        assertEquals(3, chunks.size());
        assertEquals("第一句。", chunks.get(0));
        assertEquals("第二句。", chunks.get(1));
        assertEquals("第三句。", chunks.get(2));
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 4));
    }

    @Test
    void keepsEachChunkWithinChunkSizeForShortParagraphs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("p").append(i).append("x".repeat(58)).append("\n\n");
        }

        List<String> chunks = ragService.chunk(sb.toString(), 100, 0);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().noneMatch(String::isBlank));
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 100),
            "chunks larger than chunkSize would blow the embedding context window");
    }

    @Test
    void overlapAppendsStartOfNextChunk() {
        List<String> chunks = ragService.chunk("abc\n\ndef", 2, 1);

        assertEquals(2, chunks.size());
        assertEquals("abc\n\nd", chunks.get(0));
        assertEquals("def", chunks.get(1));
    }

    @Test
    void emptyOrBlankContentProducesNoChunks() {
        assertTrue(ragService.chunk("", 1024, 128).isEmpty());
        assertTrue(ragService.chunk("\n\n  \n\n", 1024, 128).isEmpty());
    }

    @Test
    void overlapShorterThanNextChunkTakesAvailableText() {
        List<String> chunks = ragService.chunk("ab\n\ncd", 2, 10);

        assertEquals(2, chunks.size());
        assertEquals("ab\n\ncd", chunks.get(0));
        assertEquals("cd", chunks.get(1));
    }
}
