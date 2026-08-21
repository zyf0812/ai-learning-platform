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
    void setUp() throws Exception {
        // 使用反射创建实例（避免 @Value 注入）
        java.lang.reflect.Constructor<RAGService> ctor = RAGService.class.getDeclaredConstructor(
            DocumentChunkMapper.class, String.class, String.class, String.class, int.class
        );
        ctor.setAccessible(true);
        ragService = ctor.newInstance(chunkMapper, "test-key", "http://localhost", "embedding-3", 256);
    }

    // ==================== chunk: 段落切分 ====================

    @Test
    void chunk_singleShortParagraph_oneChunk() {
        String content = "这是一个短段落，长度远小于 chunkSize。";
        List<String> chunks = ragService.chunk(content, 1000, 0);
        assertEquals(1, chunks.size());
        assertEquals(content.trim(), chunks.get(0));
    }

    @Test
    void chunk_multipleParagraphsSplit() {
        String p1 = "第一段内容。".repeat(5);   // ~30字
        String p2 = "第二段内容。".repeat(5);
        String content = p1 + "\n\n" + p2;

        // chunkSize 设小一点（50字），两段分开
        List<String> chunks = ragService.chunk(content, 50, 0);
        assertTrue(chunks.size() >= 2, "两段应被分成不同块");
    }

    @Test
    void chunk_emptyContent_noCrash() {
        assertEquals(List.of(), ragService.chunk("", 100, 0));
        assertEquals(List.of(), ragService.chunk("\n\n\n\n", 100, 0));
    }

    @Test
    void chunk_emptyParagraphsSkipped() {
        String content = "\n\n第一段。\n\n\n\n第二段。\n\n";
        List<String> chunks = ragService.chunk(content, 1000, 0);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("第一段"));
        assertTrue(chunks.get(1).startsWith("第二段"));
    }

    // ==================== chunk: 超长段落按句子切分 ====================

    @Test
    void chunk_longParagraphSplitBySentence() {
        // 构造一个超长段落（> chunkSize），含多句
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longPara.append("这是第").append(i).append("句话。每句15字左右。");
        }
        String content = longPara.toString();

        List<String> chunks = ragService.chunk(content, 50, 0);
        assertTrue(chunks.size() > 1, "超长段落应按句子切分成多块");

        // 所有块拼接后，基本保留原内容（句子不丢失）
        String joined = String.join("", chunks);
        for (int i = 0; i < 20; i++) {
            assertTrue(joined.contains("第" + i + "句话"), "每句都应出现在某个块中");
        }
    }

    @Test
    void chunk_mixedChineseEnglishSentencePunctuation() {
        // 测试句子切分支持中英文标点 。！？ . ! ?
        String content = "句子一！句子二? 句子三. 句子四。五。";
        List<String> chunks = ragService.chunk(content, 15, 0);
        assertTrue(chunks.size() >= 1);
        String joined = String.join("", chunks);
        for (String s : List.of("句子一", "句子二", "句子三", "句子四")) {
            assertTrue(joined.contains(s));
        }
    }

    // ==================== chunk: overlap 重叠 ====================

    @Test
    void chunk_overlapAddsTrailingContext() {
        String p1 = "A段内容。".repeat(10);
        String p2 = "B段内容。".repeat(10);
        String p3 = "C段内容。".repeat(10);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        // 强制分段（chunkSize=50）并加 overlap=30
        List<String> overlapped = ragService.chunk(content, 50, 30);
        List<String> plain = ragService.chunk(content, 50, 0);

        // 块数相同
        assertEquals(plain.size(), overlapped.size());

        // 前 N-1 块的 overlapped 版本应更长（追加了下一块开头）
        for (int i = 0; i < overlapped.size() - 1; i++) {
            assertTrue(overlapped.get(i).length() >= plain.get(i).length(),
                "带 overlap 的块应更长或相等");
        }
    }

    @Test
    void chunk_lastChunkNoOverlap() {
        String p1 = "A".repeat(30);
        String p2 = "B".repeat(30);
        String content = p1 + "\n\n" + p2;
        List<String> chunks = ragService.chunk(content, 20, 10);
        // 最后一块不应该有下一块的重叠
        String last = chunks.get(chunks.size() - 1);
        // 最后一块应只包含 B 段内容（不应出现大量 A）
        assertTrue(last.chars().filter(c -> c == 'B').count()
                 > last.chars().filter(c -> c == 'A').count());
    }

    @Test
    void chunk_zeroOrNegativeOverlap_plainResult() {
        String content = "P1段落。\n\nP2段落。\n\nP3段落。";
        List<String> c1 = ragService.chunk(content, 10, 0);
        List<String> c2 = ragService.chunk(content, 10, -5);
        assertEquals(c1, c2);
    }

    @Test
    void chunk_singleChunkNoOverlapAdded() {
        String content = "单段内容，很短。";
        List<String> with = ragService.chunk(content, 1000, 500);
        List<String> without = ragService.chunk(content, 1000, 0);
        assertEquals(1, with.size());
        assertEquals(without, with);
    }

    // ==================== toVectorString ====================

    @Test
    void toVectorString_validFormat() {
        float[] vec = { 1.5f, -2.25f, 0.0f, 3.14f };
        String result = ragService.toVectorString(vec);
        assertTrue(result.startsWith("[") && result.endsWith("]"));
        assertTrue(result.contains("1.5"));
        assertTrue(result.contains("-2.25"));
        assertTrue(result.contains("3.14"));
    }

    @Test
    void toVectorString_emptyArray() {
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }
}
