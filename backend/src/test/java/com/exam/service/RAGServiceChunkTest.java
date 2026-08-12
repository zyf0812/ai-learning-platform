package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAGService.chunk() 文档分块算法测试。
 * 分块是 RAG 检索质量的基础，边界条件众多。
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceChunkTest {

    @Mock private DocumentChunkMapper chunkMapper;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        // 通过反射注入私有 final 字段（绕过构造器的 @Value）
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "embedding-3", 1024);
    }

    // ========== 基础段落切分 ==========

    @Test
    void chunkSingleShortParagraph() {
        String content = "一个短段落，不超过分块大小。";

        List<String> chunks = ragService.chunk(content, 1024, 0);

        assertEquals(1, chunks.size());
        assertEquals("一个短段落，不超过分块大小。", chunks.get(0));
    }

    @Test
    void chunkMultipleShortParagraphsFitsInOneChunk() {
        String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。";

        List<String> chunks = ragService.chunk(content, 200, 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段"));
        assertTrue(chunks.get(0).contains("第三段"));
    }

    @Test
    void chunkMultipleParagraphsSplitAcrossChunks() {
        // 两段各150字，chunkSize=200 -> 应被分到两个块
        String p1 = repeat("A", 150);
        String p2 = repeat("B", 150);
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 200, 0);

        assertTrue(chunks.size() >= 2, "块数: " + chunks.size());
        assertTrue(chunks.get(0).contains("AAA"));
        assertTrue(chunks.get(chunks.size() - 1).contains("BBB"));
    }

    @Test
    void chunkEmptyStringReturnsEmptyList() {
        assertTrue(ragService.chunk("", 100, 0).isEmpty());
    }

    @Test
    void chunkOnlyWhitespaceParagraphs() {
        assertTrue(ragService.chunk("\n\n  \n\n\t\n\n", 100, 0).isEmpty());
    }

    // ========== 超长段落按句子切分 ==========

    @Test
    void chunkSingleVeryLongParagraphSplitBySentences() {
        // 构造超长段落（> chunkSize），含多个句号分隔的句子
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("第").append(i).append("句，大约三十个字左右作为测试内容。");
        }
        String content = sb.toString();
        assertTrue(content.length() > 100, "段落应足够长");

        List<String> chunks = ragService.chunk(content, 100, 0);

        assertTrue(chunks.size() >= 2, "超长段落应被切分，实际块数: " + chunks.size());
        // 每个块不超过 chunkSize 太多（句子切分是追加式，可能稍大于 chunkSize）
        for (String c : chunks) {
            assertTrue(c.length() < 160, "块过大: " + c.length());
        }
    }

    @Test
    void chunkLongParagraphSplitOnChinesePeriod() {
        String s1 = repeat("一", 40) + "。";
        String s2 = repeat("二", 40) + "。";
        String s3 = repeat("三", 40) + "。";
        // 单段3句，每句41字，chunkSize=70 -> 应分多块
        String content = s1 + s2 + s3;

        List<String> chunks = ragService.chunk(content, 70, 0);

        assertTrue(chunks.size() >= 2, "块数: " + chunks.size());
    }

    @Test
    void chunkLongParagraphSplitOnEnglishPeriod() {
        String s1 = "This is a rather long English sentence for testing purposes. ";
        String s2 = "Another sentence that follows to build up paragraph length. ";
        String s3 = "And a third sentence to make sure we exceed chunkSize. ";
        String content = s1.repeat(4) + s2.repeat(4) + s3.repeat(4);

        List<String> chunks = ragService.chunk(content, 120, 0);
        assertTrue(chunks.size() >= 2);
    }

    @Test
    void chunkLongParagraphSplitOnExclamationAndQuestion() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i % 3 == 0) sb.append("句子A！");
            else if (i % 3 == 1) sb.append("句子B？");
            else sb.append("句子C。");
        }
        String content = sb.toString();

        List<String> chunks = ragService.chunk(content, 60, 0);
        assertTrue(chunks.size() >= 2);
    }

    // ========== overlap 重叠逻辑 ==========

    @Test
    void chunkWithOverlapAppendsNextChunkStart() {
        String p1 = repeat("X", 80);
        String p2 = repeat("Y", 80);
        String p3 = repeat("Z", 80);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = ragService.chunk(content, 100, 20);

        assertTrue(chunks.size() >= 3, "应至少3块，实际: " + chunks.size());

        // 第1块末尾应包含第2块开头的YYY
        String c0 = chunks.get(0);
        assertTrue(c0.contains("YYY") || c0.endsWith("Y".repeat(20)),
            "块0应包含overlap: " + c0.substring(Math.max(0, c0.length() - 30)));

        // 第2块末尾应包含第3块开头的ZZZ
        String c1 = chunks.get(1);
        assertTrue(c1.contains("ZZZ") || c1.endsWith("Z".repeat(20)),
            "块1应包含overlap: " + c1.substring(Math.max(0, c1.length() - 30)));

        // 最后一块不应有额外 overlap 追加（没有下一块）
        String last = chunks.get(chunks.size() - 1);
        assertFalse(last.endsWith("\n\n"));
    }

    @Test
    void chunkWithOverlapSmallerThanNextChunkStart() {
        // overlap 大于下一个块的长度，取下一个块全部
        String p1 = repeat("A", 50);
        String p2 = repeat("B", 10); // 只有10字符，overlap=20 取全部
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 60, 20);

        assertTrue(chunks.size() >= 2);
        String c0 = chunks.get(0);
        // 第一块应包含 B 的全部(10个) 作为 overlap
        assertTrue(c0.contains("BBBBBBBBBB"), "c0=" + c0);
    }

    @Test
    void chunkOverlapZeroNoOverlapAdded() {
        String p1 = repeat("M", 60);
        String p2 = repeat("N", 60);
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 70, 0);

        assertEquals(2, chunks.size());
        assertFalse(chunks.get(0).contains("NNN"),
            "overlap=0不应包含下一块内容: " + chunks.get(0));
    }

    @Test
    void chunkSingleChunkNoOverlapNeeded() {
        String content = "一段不长的内容，不会被分成多块。";

        List<String> chunks = ragService.chunk(content, 200, 50);

        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0));
    }

    // ========== toVectorString 向量格式化 ==========

    @Test
    void toVectorStringEmptyArray() {
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }

    @Test
    void toVectorStringSingleElement() {
        assertEquals("[1.5]", ragService.toVectorString(new float[]{1.5f}));
    }

    @Test
    void toVectorStringMultipleElements() {
        String s = ragService.toVectorString(new float[]{1.0f, -2.5f, 3.14f});
        assertEquals("[1.0,-2.5,3.14]", s);
    }

    // ========== helper ==========

    private String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
