package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() throws Exception {
        ragService = new RAGService(chunkMapper, "fake-key", "http://localhost", "embedding-model", 1024);
    }

    // ===== chunk 分段算法 =====

    @Test
    void chunkEmptyStringReturnsEmpty() {
        List<String> chunks = ragService.chunk("", 1024, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkSingleShortParagraphSingleChunk() {
        String text = "这是一个短段落。";
        List<String> chunks = ragService.chunk(text, 1024, 0);
        assertEquals(1, chunks.size());
        assertEquals("这是一个短段落。", chunks.get(0));
    }

    @Test
    void chunkMultipleShortParagraphsMerged() {
        String text = "段落一内容。\n\n段落二内容。\n\n段落三内容。";
        List<String> chunks = ragService.chunk(text, 100, 0);
        // 三个短段落总长不大，应合并为一个块
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("段落一"));
        assertTrue(chunks.get(0).contains("段落二"));
        assertTrue(chunks.get(0).contains("段落三"));
    }

    @Test
    void chunkMultipleParagraphsSplitWhenExceedsSize() {
        StringBuilder sb = new StringBuilder();
        // 每个段落 30 字符左右，chunkSize=50 → 每个段落一个块
        for (int i = 1; i <= 5; i++) {
            sb.append("这是第").append(i).append("段内容，用来测试分段逻辑是否正确。");
            sb.append("\n\n");
        }
        List<String> chunks = ragService.chunk(sb.toString(), 50, 0);
        assertTrue(chunks.size() >= 3, "至少应分成3个块: " + chunks.size());
    }

    @Test
    void chunkSingleLongParagraphSplitsBySentences() {
        // 构造一个超长段落，按句号切分
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("第").append(i).append("句：这是一句话。");
        }
        String longPara = sb.toString();
        // 段内以句号分割，chunkSize=100
        List<String> chunks = ragService.chunk(longPara, 100, 0);
        assertTrue(chunks.size() > 1, "长段落应被切分为多个块，实际: " + chunks.size());
        // 所有块拼接起来应该包含原文的大部分句子
        StringBuilder merged = new StringBuilder();
        for (String c : chunks) merged.append(c);
        for (int i = 1; i <= 20; i++) {
            assertTrue(merged.toString().contains("第" + i + "句"),
                "合并后应包含第" + i + "句");
        }
    }

    @Test
    void chunkWithOverlapAppendsTailOfNextChunk() {
        // 构造多段落，确保产生多个块
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("段落P").append(i).append("：这是第").append(i).append("个段落的测试内容，用于验证overlap逻辑。");
            sb.append("\n\n");
        }
        List<String> chunks = ragService.chunk(sb.toString(), 80, 20);
        assertTrue(chunks.size() >= 2, "至少需要2个块才能测试overlap");

        for (int i = 0; i < chunks.size() - 1; i++) {
            String curr = chunks.get(i);
            String next = chunks.get(i + 1);
            // 非末尾块应包含下一块的开头 overlap 部分
            String nextHead = next.substring(0, Math.min(20, next.length()));
            assertTrue(curr.endsWith(nextHead) || curr.contains(nextHead),
                "块 " + i + " 尾部应包含下一块开头的 overlap 内容\n块" + i + "末尾: ..." +
                    curr.substring(Math.max(0, curr.length() - 50)) + "\n下块开头: " + nextHead);
        }
    }

    @Test
    void chunkNoOverlapWhenOnlyOneChunk() {
        String text = "短段落不产生overlap。";
        List<String> chunks = ragService.chunk(text, 1024, 128);
        assertEquals(1, chunks.size());
        assertEquals("短段落不产生overlap。", chunks.get(0));
    }

    @Test
    void chunkOverlapZeroNoOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append("P").append(i).append("内容内容内容内容内容内容内容内容内容内容内容。");
            sb.append("\n\n");
        }
        List<String> withZero = ragService.chunk(sb.toString(), 50, 0);
        List<String> withOverlap = ragService.chunk(sb.toString(), 50, 20);
        // overlap=0 的每个块比 overlap>0 的对应块短（或等长）
        for (int i = 0; i < Math.min(withZero.size(), withOverlap.size()) - 1; i++) {
            assertTrue(withOverlap.get(i).length() >= withZero.get(i).length(),
                "overlap>0的块应更长或等长");
        }
    }

    // ===== toVectorString =====

    @Test
    void toVectorStringEmptyArray() {
        String s = ragService.toVectorString(new float[0]);
        assertEquals("[]", s);
    }

    @Test
    void toVectorStringSingleElement() {
        String s = ragService.toVectorString(new float[]{1.5f});
        assertEquals("[1.5]", s);
    }

    @Test
    void toVectorStringMultipleElements() {
        String s = ragService.toVectorString(new float[]{1.0f, -2.5f, 3.25f});
        assertEquals("[1.0,-2.5,3.25]", s);
    }

    // ===== extractStructure 异常保护 =====

    @Test
    void extractStructureOnMapperErrorReturnsEmpty() {
        // chunkMapper 没有 mock 具体行为 -> 默认返回 null 或抛异常
        // 方法有 try-catch，应返回空字符串
        String result = ragService.extractStructure("any-doc-id");
        assertEquals("", result);
    }

    @Test
    void searchByDocumentOnErrorReturnsEmptyList() {
        // embed 会因为 URL 无效而异常，应返回空列表
        List<String> result = ragService.searchByDocument("doc1", "query", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchAllOnErrorReturnsEmptyList() {
        List<String> result = ragService.searchAll("query", 5);
        assertTrue(result.isEmpty());
    }
}
