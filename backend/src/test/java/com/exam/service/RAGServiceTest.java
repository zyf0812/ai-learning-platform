package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://test-url", "test-model", 1024);
    }

    @Nested
    @DisplayName("chunk() tests")
    class ChunkTests {

        @Test
        @DisplayName("chunks text by paragraph")
        void chunksByParagraph() {
            String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
            List<String> chunks = ragService.chunk(content, 100, 0);

            assertEquals(3, chunks.size());
            assertTrue(chunks.get(0).contains("第一段内容"));
            assertTrue(chunks.get(1).contains("第二段内容"));
            assertTrue(chunks.get(2).contains("第三段内容"));
        }

        @Test
        @DisplayName("chunks long paragraph by sentences")
        void chunksLongParagraph() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("这是第").append(i).append("句内容。");
            }
            String content = sb.toString();

            List<String> chunks = ragService.chunk(content, 200, 0);

            assertFalse(chunks.isEmpty());
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= 200 || chunk.equals(chunks.get(chunks.size() - 1)),
                    "Chunk should not exceed chunk size");
            }
        }

        @Test
        @DisplayName("chunks with overlap add context")
        void chunksWithOverlap() {
            String content = "第一段。\n\n第二段。\n\n第三段。";
            List<String> chunks = ragService.chunk(content, 50, 10);

            assertEquals(3, chunks.size());
        }

        @Test
        @DisplayName("empty content returns empty list")
        void emptyContent() {
            List<String> chunks = ragService.chunk("", 100, 0);
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("whitespace-only content returns empty list")
        void whitespaceOnly() {
            List<String> chunks = ragService.chunk("   \n\n  \n  ", 100, 0);
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("single short paragraph returns single chunk")
        void singleParagraph() {
            List<String> chunks = ragService.chunk("这是一段短文本。", 1000, 0);
            assertEquals(1, chunks.size());
            assertTrue(chunks.get(0).contains("这是一段短文本"));
        }

        @Test
        @DisplayName("overlap is not applied when chunks.size <= 1")
        void overlapNotAppliedForSingleChunk() {
            List<String> chunks = ragService.chunk("短文本内容。", 1000, 50);
            assertEquals(1, chunks.size());
        }

        @Test
        @DisplayName("long text chunking respects size limit")
        void longTextChunking() {
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                longText.append("这是第").append(i + 1).append("段内容，用于测试分块功能。\n\n");
            }

            List<String> chunks = ragService.chunk(longText.toString(), 100, 0);

            assertFalse(chunks.isEmpty());
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= 100,
                    "Chunk length " + chunk.length() + " should not exceed 100");
            }
        }
    }

    @Nested
    @DisplayName("toVectorString() tests")
    class ToVectorStringTests {

        @Test
        @DisplayName("converts float array to vector string")
        void convertsToArrayString() {
            float[] vec = {0.1f, 0.2f, 0.3f};
            String result = ragService.toVectorString(vec);

            assertEquals("[0.1,0.2,0.3]", result);
        }

        @Test
        @DisplayName("handles empty array")
        void handlesEmptyArray() {
            float[] vec = {};
            String result = ragService.toVectorString(vec);

            assertEquals("[]", result);
        }

        @Test
        @DisplayName("handles single element")
        void handlesSingleElement() {
            float[] vec = {0.5f};
            String result = ragService.toVectorString(vec);

            assertEquals("[0.5]", result);
        }

        @Test
        @DisplayName("handles negative values")
        void handlesNegativeValues() {
            float[] vec = {-0.1f, 0.5f, -0.3f};
            String result = ragService.toVectorString(vec);

            assertEquals("[-0.1,0.5,-0.3]", result);
        }
    }
}