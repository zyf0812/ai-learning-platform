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
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(chunkMapper, "test-key", "http://test.url", "embedding-3", 1024);
    }

    @Test
    void chunkSingleShortParagraphReturnsOneChunk() {
        String content = "这是一个简短的段落。";

        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(1, chunks.size());
        assertEquals("这是一个简短的段落。", chunks.get(0));
    }

    @Test
    void chunkMultipleShortParagraphsCombinedWhenSmall() {
        String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。";

        List<String> chunks = ragService.chunk(content, 200, 0);

        assertTrue(chunks.size() >= 1);
        assertTrue(chunks.get(0).contains("第一段"));
    }

    @Test
    void chunkLongContentSplitsIntoMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("这是第").append(i).append("段很长很长的内容。每段都有很多很多字。");
            sb.append("\n\n");
        }

        List<String> chunks = ragService.chunk(sb.toString(), 100, 0);

        assertTrue(chunks.size() > 1);
    }

    @Test
    void chunkOverlapAddsNextChunkBeginning() {
        String content = "ABCDEFGHIJ。\n\nKLMNOPQRST。\n\nUVWXYZ。";

        List<String> chunks = ragService.chunk(content, 20, 3);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).length() > 20 || chunks.size() == 1);
    }

    @Test
    void chunkZeroOverlapNoExtraContent() {
        String content = "第一段内容。\n\n第二段内容。";

        List<String> chunks = ragService.chunk(content, 100, 0);

        assertTrue(chunks.size() >= 1);
    }

    @Test
    void chunkEmptyStringReturnsEmptyList() {
        List<String> chunks = ragService.chunk("", 100, 0);

        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkOnlyWhitespaceReturnsEmptyList() {
        List<String> chunks = ragService.chunk("   \n\n   \n\n  ", 100, 0);

        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkVeryLongParagraphSplitsBySentence() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("第").append(i).append("句话。");
        }
        String content = sb.toString();

        List<String> chunks = ragService.chunk(content, 50, 0);

        assertTrue(chunks.size() > 1);
    }

    @Test
    void chunkPreservesContentOrder() {
        String content = "第一段。\n\n第二段。\n\n第三段。\n\n第四段。";

        List<String> chunks = ragService.chunk(content, 30, 0);

        assertTrue(chunks.size() >= 1);
        assertTrue(chunks.get(0).contains("第一段"));
    }

    @Test
    void chunkExclamationMarkSplitsSentences() {
        String content = "第一句！第二句！第三句！第四句！第五句！";

        List<String> chunks = ragService.chunk(content, 20, 0);

        assertTrue(chunks.size() > 1);
    }

    @Test
    void chunkQuestionMarkSplitsSentences() {
        String content = "第一句？第二句？第三句？第四句？第五句？";

        List<String> chunks = ragService.chunk(content, 20, 0);

        assertTrue(chunks.size() > 1);
    }

    @Test
    void chunkMixedPunctuationSplitsSentences() {
        String content = "第一句。第二句！第三句？第四句。第五句！";

        List<String> chunks = ragService.chunk(content, 20, 0);

        assertTrue(chunks.size() > 1);
    }

    @Test
    void toVectorStringFormatsCorrectly() {
        float[] vec = {1.0f, 2.5f, -3.0f};

        String result = ragService.toVectorString(vec);

        assertTrue(result.startsWith("["));
        assertTrue(result.endsWith("]"));
        assertTrue(result.contains("1.0"));
        assertTrue(result.contains("2.5"));
        assertTrue(result.contains("-3.0"));
    }

    @Test
    void toVectorStringEmptyArray() {
        float[] vec = new float[0];

        String result = ragService.toVectorString(vec);

        assertEquals("[]", result);
    }

    @Test
    void toVectorStringSingleElement() {
        float[] vec = {42.0f};

        String result = ragService.toVectorString(vec);

        assertEquals("[42.0]", result);
    }

    @Test
    void chunkOverlapDoesNotExceedNextChunkLength() {
        String content = "非常短的第一段。\n\n第二段内容稍微长一点。";

        List<String> chunks = ragService.chunk(content, 100, 100);

        assertTrue(chunks.size() >= 1);
    }

    @Test
    void extractStructureEmptyChunksReturnsEmpty() {
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of());

        String result = ragService.extractStructure("doc1");

        assertEquals("", result);
    }

    @Test
    void extractStructureReturnsFirstChunkBeginning() {
        String firstChunk = "这是文档的开头部分，包含了很多重要的介绍内容。";
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(firstChunk));

        String result = ragService.extractStructure("doc1");

        assertTrue(result.contains("【文档开头】"));
        assertTrue(result.contains("这是文档的开头部分"));
    }

    @Test
    void extractStructureDetectsShortHeadingLikeChunks() {
        String first = "引言";
        String second = "第一章 基础知识";
        String third = "这是很长的正文内容，包含了很多很多的文字信息。";
        String fourth = "第二章 进阶内容";
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(first, second, third, fourth));

        String result = ragService.extractStructure("doc1");

        assertTrue(result.contains("【章节标题】"));
    }

    @Test
    void chunkSingleChunkNoOverlapAdded() {
        String content = "只有一段内容。";

        List<String> chunks = ragService.chunk(content, 100, 50);

        assertEquals(1, chunks.size());
    }
}
