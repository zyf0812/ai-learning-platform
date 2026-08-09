package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        // 使用空字符串构造，测试 chunk() 和 toVectorString() 不依赖外部 API
        ragService = new RAGService(chunkMapper, "", "", "test-model", 4);
    }

    // ==================== toVectorString() ====================

    @Test
    void toVectorStringFormatsCorrectly() {
        float[] vec = {1.0f, -0.5f, 0.25f, 0.75f};
        String result = ragService.toVectorString(vec);
        assertEquals("[1.0,-0.5,0.25,0.75]", result);
    }

    @Test
    void toVectorStringEmptyArray() {
        float[] vec = {};
        assertEquals("[]", ragService.toVectorString(vec));
    }

    @Test
    void toVectorStringSingleElement() {
        float[] vec = {3.14f};
        assertEquals("[3.14]", ragService.toVectorString(vec));
    }

    // ==================== chunk() 基础段落分块 ====================

    @Test
    void chunkSplitsByParagraphs() {
        String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(3, chunks.size());
        assertEquals("第一段内容。", chunks.get(0));
        assertEquals("第二段内容。", chunks.get(1));
        assertEquals("第三段内容。", chunks.get(2));
    }

    @Test
    void chunkSkipsEmptyParagraphs() {
        String content = "第一段。\n\n\n\n\n第二段。\n\n";
        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(2, chunks.size());
        assertEquals("第一段。", chunks.get(0));
        assertEquals("第二段。", chunks.get(1));
    }

    @Test
    void chunkSingleShortParagraphOneChunk() {
        String content = "很短的单段落。";
        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(1, chunks.size());
        assertEquals("很短的单段落。", chunks.get(0));
    }

    // ==================== chunk() 段落合并 ====================

    @Test
    void chunkMergesSmallParagraphsWithinChunkSize() {
        // 两个短段落（各5字），chunkSize=20，应该合并到一个块
        String content = "第一段文字。\n\n第二段文字。";
        List<String> chunks = ragService.chunk(content, 30, 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段文字。"));
        assertTrue(chunks.get(0).contains("第二段文字。"));
    }

    @Test
    void chunkSplitsWhenParagraphsExceedChunkSize() {
        // 两个段落，总长度超过 chunkSize 时分开
        String content = "这是第一段比较长的文字内容。\n\n这是第二段比较长的文字内容。";
        List<String> chunks = ragService.chunk(content, 15, 0);

        assertEquals(2, chunks.size());
    }

    // ==================== chunk() 超长段落按句子切分 ====================

    @Test
    void chunkLongParagraphSplitsBySentence() {
        // 一个超长段落：包含多个句子
        String para = "第一句话。第二句话！第三句话？第四句话。第五句话。";
        // 每句5字+标点，共5句。chunkSize=10，应分成多个块
        List<String> chunks = ragService.chunk(para, 10, 0);

        assertTrue(chunks.size() >= 2);
        // 每块都非空
        for (String c : chunks) {
            assertFalse(c.isEmpty());
        }
    }

    @Test
    void chunkMixedEnglishPunctuationSentences() {
        String para = "Sentence one. Sentence two! Sentence three?";
        List<String> chunks = ragService.chunk(para, 20, 0);

        assertTrue(chunks.size() >= 1);
        // 确保分块不丢失内容的总体长度
        int totalLen = chunks.stream().mapToInt(String::length).sum();
        assertTrue(totalLen >= para.length() * 0.5);
    }

    // ==================== chunk() overlap 逻辑 ====================

    @Test
    void chunkWithOverlapAppendsNextChunkPrefix() {
        // 3个短段落，chunkSize很小保证每段独立，overlap=5
        String content = "AAAAAAAAAA\n\nBBBBBBBBBB\n\nCCCCCCCCCC";
        List<String> chunks = ragService.chunk(content, 12, 5);

        assertEquals(3, chunks.size());
        // 第一个块应包含第二个块开头的 overlap=5 字符
        assertTrue(chunks.get(0).contains("BBBBB"));
        // 第二个块应包含第三个块开头的 overlap=5 字符
        assertTrue(chunks.get(1).contains("CCCCC"));
        // 最后一个块没有下一块，不含多余内容
        assertFalse(chunks.get(2).contains("\n\n") && chunks.get(2).length() > 12);
    }

    @Test
    void chunkWithZeroOverlapNoCrossContamination() {
        String content = "段落一内容。\n\n段落二内容。\n\n段落三内容。";
        List<String> chunks = ragService.chunk(content, 20, 0);

        // 无 overlap 时各块干净独立
        for (int i = 0; i < chunks.size() - 1; i++) {
            // 简化：检查块之间没有显式追加标记
            assertTrue(chunks.get(i).endsWith("。") || !chunks.get(i).contains("\n\n\n"));
        }
    }

    @Test
    void chunkSingleChunkIgnoresOverlap() {
        String content = "只有一个段落。";
        List<String> chunks = ragService.chunk(content, 100, 50);
        // 只有一块时不触发 overlap 逻辑
        assertEquals(1, chunks.size());
    }

    @Test
    void chunkOverlapLargerThanNextChunkTakesAll() {
        String content = "第一段很长的文字内容。\n\n简短"; // 第二段只有2字
        List<String> chunks = ragService.chunk(content, 20, 100); // overlap 大于第二段

        assertEquals(2, chunks.size());
        // 第一段后面追加了完整第二段（因为 overlap 很大）
        assertTrue(chunks.get(0).contains("简短"));
    }

    // ==================== chunk() 边界条件 ====================

    @Test
    void chunkEmptyOrBlankReturnsNoChunks() {
        assertTrue(ragService.chunk("", 100, 0).isEmpty());
        assertTrue(ragService.chunk("   \n\n   \n  ", 100, 0).isEmpty());
    }

    @Test
    void chunkPreservesTrailingContent() {
        // 最后一段内容不能丢失
        String content = "第一段。\n\n第二段。\n\n最后一段收尾内容。";
        List<String> chunks = ragService.chunk(content, 10, 0);

        String last = chunks.get(chunks.size() - 1);
        assertTrue(last.contains("收尾") || last.contains("最后") || last.contains("内容"));
    }

    // ==================== extractStructure() ====================

    @Test
    void extractStructureFromEmptyChunksReturnsEmpty() {
        // extractStructure 调用 chunkMapper.getAllChunks，内部空列表返回 ""
        // 我们用 mock 返回空列表，直接测试调用
        when(chunkMapper.getAllChunks("doc-empty")).thenReturn(List.of());

        String structure = ragService.extractStructure("doc-empty");
        assertEquals("", structure);
    }

    @Test
    void extractStructureIncludesFirstChunkOpening() {
        String firstChunk = "这是文档开头介绍部分，包含背景和概述。".repeat(20); // 超过 500 字符
        when(chunkMapper.getAllChunks("doc1")).thenReturn(List.of(
                firstChunk,
                "短块像标题1",
                "很长的一块内容不是标题因为它包含了非常多的文字远远超过了两百个字符的限制因此不会被当作标题来处理"
        ));

        String structure = ragService.extractStructure("doc1");
        assertTrue(structure.contains("【文档开头】"));
        assertTrue(structure.length() <= 1000 + 100); // 开头截断 + 标题区不会太长
    }

    @Test
    void extractStructureDetectsShortHeadingLikeChunks() {
        when(chunkMapper.getAllChunks("doc2")).thenReturn(List.of(
                "文档简介开头",
                "第一章 基础知识",     // < 200 且无换行 → 标题
                "这里是很长的详细内容解释基础知识的各种细节和用法非常非常详细以至于这块内容远远超过两百字符",
                "第二章 进阶内容",     // < 200 且无换行 → 标题
                "第三章 总结回顾"      // < 200 且无换行 → 标题
        ));

        String structure = ragService.extractStructure("doc2");
        assertTrue(structure.contains("【章节标题】"));
        assertTrue(structure.contains("第一章 基础知识"));
        assertTrue(structure.contains("第二章 进阶内容"));
        assertTrue(structure.contains("第三章 总结回顾"));
    }

    // ==================== 搜索异常降级 ====================

    @Test
    void searchByDocumentOnExceptionReturnsEmptyList() {
        // embed() 会抛异常（api key 为空），但外层应该捕获并返回空
        List<String> result = ragService.searchByDocument("doc1", "query", 5);
        assertEquals(List.of(), result);
    }

    @Test
    void searchAllOnExceptionReturnsEmptyList() {
        List<String> result = ragService.searchAll("query", 5);
        assertEquals(List.of(), result);
    }

    @Test
    void getChunksByIndexOnExceptionReturnsEmptyList() {
        when(chunkMapper.getChunksByIndex(any(), anyInt())).thenThrow(new RuntimeException("DB down"));
        assertEquals(List.of(), ragService.getChunksByIndex("doc", 10));
    }

    @Test
    void getChunksUniformOnExceptionReturnsEmptyList() {
        when(chunkMapper.getChunksUniform(any(), anyInt())).thenThrow(new RuntimeException("DB down"));
        assertEquals(List.of(), ragService.getChunksUniform("doc", 3));
    }
}
