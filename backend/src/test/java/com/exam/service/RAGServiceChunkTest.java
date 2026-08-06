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
class RAGServiceChunkTest {

    @Mock
    private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() throws Exception {
        // 使用反射构造 RAGService（避免需要 @Value 字段）
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "embedding-2", 1024);
    }

    // --- chunk() 基础切分测试 ---

    @Test
    void chunkSplitsByParagraphs() {
        String content = "第一段内容，介绍Java基础。\n\n"
            + "第二段内容，介绍Spring框架。\n\n"
            + "第三段内容，介绍MyBatis持久层。";

        List<String> chunks = ragService.chunk(content, 500, 0);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).contains("Java"));
        assertTrue(chunks.get(1).contains("Spring"));
        assertTrue(chunks.get(2).contains("MyBatis"));
    }

    @Test
    void chunkEmptyContentReturnsEmpty() {
        assertTrue(ragService.chunk("", 100, 0).isEmpty());
        assertTrue(ragService.chunk("\n\n\n\n", 100, 0).isEmpty());
    }

    @Test
    void chunkSingleShortParagraphReturnsOneChunk() {
        String content = "这是一段简短的内容。";
        List<String> chunks = ragService.chunk(content, 500, 0);
        assertEquals(1, chunks.size());
        assertEquals("这是一段简短的内容。", chunks.get(0));
    }

    @Test
    void chunkLongParagraphSplitsBySentences() {
        // 构造一个超长段落（超过 chunkSize），里面有多个句子
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("这是第").append(i).append("句话，用来测试句子级切分。");
        }
        String content = sb.toString();

        List<String> chunks = ragService.chunk(content, 80, 0);
        // 应该被切成多块
        assertTrue(chunks.size() > 1);
        // 每块都应该有内容
        for (String c : chunks) {
            assertFalse(c.isBlank());
        }
    }

    @Test
    void chunkLongEnglishParagraphSplitsBySentences() {
        String content = "This is the first sentence about Java. "
            + "Spring Boot makes it easy to create stand-alone applications. "
            + "MyBatis is a persistence framework. "
            + "JUnit is used for unit testing. "
            + "Mockito helps create mock objects.";
        // 重复几次使其变长
        String longContent = content + " " + content + " " + content;

        List<String> chunks = ragService.chunk(longContent, 100, 0);
        assertTrue(chunks.size() > 1);
    }

    @Test
    void chunkMultipleMixedLengthParagraphs() {
        // 混合：短段落 + 超长段落 + 短段落
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            longPara.append("句子").append(i).append("。");
        }

        String content = "短介绍段落。\n\n"
            + longPara.toString() + "\n\n"
            + "结尾总结段落。";

        List<String> chunks = ragService.chunk(content, 60, 0);
        // 至少应有 3 块（短段落1 + 长段落切成的多块 + 结尾段落）
        assertTrue(chunks.size() >= 3);
        // 第一块是短介绍
        assertTrue(chunks.get(0).contains("短介绍"));
        // 最后一块是结尾
        assertTrue(chunks.get(chunks.size() - 1).contains("结尾总结"));
    }

    @Test
    void chunkRemovesEmptyParagraphs() {
        String content = "\n\n\n第一段内容。\n\n\n\n\n第二段内容。\n\n\n";
        List<String> chunks = ragService.chunk(content, 100, 0);
        assertEquals(2, chunks.size());
    }

    @Test
    void chunkTrimsParagraphContent() {
        String content = "   第一段前面有空格   \n\n   第二段前面也有空格   ";
        List<String> chunks = ragService.chunk(content, 100, 0);
        assertEquals(2, chunks.size());
        assertFalse(chunks.get(0).startsWith("   "));
        assertFalse(chunks.get(0).endsWith("   "));
    }

    // --- overlap 测试 ---

    @Test
    void chunkWithOverlapAppendsNextChunkBeginning() {
        String p1 = "这是第一段内容，关于数据结构。";
        String p2 = "这是第二段内容，关于算法分析，有足够的长度保证overlap生效。";
        String p3 = "这是第三段内容，关于复杂度计算。";
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = ragService.chunk(content, 300, 10);
        assertEquals(3, chunks.size());

        // 第一个chunk应该包含第二段的开头overlap部分
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("这是第二段")); // overlap 开头

        // 第二个chunk应该包含第三段的开头
        assertTrue(chunks.get(1).contains("第二段"));
        assertTrue(chunks.get(1).contains("这是第三段"));

        // 最后一个chunk没有下一段，所以没有overlap
        assertTrue(chunks.get(2).contains("第三段"));
        assertFalse(chunks.get(2).contains("第四段")); // 不存在第四段
    }

    @Test
    void chunkWithZeroOverlapNoCrossContamination() {
        String content = "AAAA\n\nBBBB\n\nCCCC";
        List<String> chunks = ragService.chunk(content, 100, 0);
        assertEquals(3, chunks.size());
        assertEquals("AAAA", chunks.get(0));
        assertEquals("BBBB", chunks.get(1));
        assertEquals("CCCC", chunks.get(2));
    }

    @Test
    void chunkWithSingleChunkNoOverlapApplied() {
        String content = "只有一个段落，所以不应该有overlap内容。";
        List<String> chunks = ragService.chunk(content, 1000, 50);
        assertEquals(1, chunks.size());
        // 没有相邻块，所以保持原样（不会重复追加自己）
        assertTrue(chunks.get(0).startsWith("只有一个段落"));
    }

    // --- toVectorString 测试 ---

    @Test
    void toVectorStringFormatsCorrectly() {
        float[] vec = {1.0f, 2.5f, -3.0f};
        String result = ragService.toVectorString(vec);
        assertEquals("[1.0,2.5,-3.0]", result);
    }

    @Test
    void toVectorStringEmptyArray() {
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }

    @Test
    void toVectorStringSingleElement() {
        assertEquals("[0.5]", ragService.toVectorString(new float[]{0.5f}));
    }

    // --- extractStructure 周边的降级行为测试 ---

    @Test
    void searchByDocumentOnExceptionReturnsEmptyList() {
        // 当 embed 调用会失败（没有配置实际的 zhipu key），应返回空列表
        List<String> result = ragService.searchByDocument("doc1", "query", 5);
        assertEquals(List.of(), result);
    }

    @Test
    void searchAllOnExceptionReturnsEmptyList() {
        List<String> result = ragService.searchAll("query", 5);
        assertEquals(List.of(), result);
    }
}
