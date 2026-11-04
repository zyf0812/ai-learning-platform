package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock private DocumentChunkMapper chunkMapper;

    private RAGService newService() {
        return new RAGService(chunkMapper, "key", "http://localhost", "embedding-3", 1024);
    }

    @Test
    void chunkCombinesParagraphsUntilSizeLimit() {
        List<String> chunks = newService().chunk("第一段\n\n第二段", 6, 0);

        assertEquals(List.of("第一段", "第二段"), chunks);
    }

    @Test
    void chunkSplitsOversizedParagraphAtSentenceBoundaries() {
        List<String> chunks = newService().chunk("第一句。第二句。第三句。", 5, 0);

        assertEquals(3, chunks.size());
        assertEquals("第一句。", chunks.get(0));
    }

    @Test
    void chunkAddsOverlapFromNextChunk() {
        List<String> chunks = newService().chunk("aaaa\n\nbbbb", 6, 2);

        assertEquals(2, chunks.size());
        assertEquals("aaaa\n\nbb", chunks.get(0));
        assertEquals("bbbb", chunks.get(1));
    }

    @Test
    void chunkReturnsEmptyListForBlankContent() {
        assertTrue(newService().chunk("\n\n  \n\n", 100, 10).isEmpty());
    }

    @Test
    void toVectorStringFormatsArrayForPgvector() {
        assertEquals("[1.5,2.0,-3.25]",
            newService().toVectorString(new float[]{1.5f, 2.0f, -3.25f}));
    }
}
