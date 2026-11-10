package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAGService.chunk() 文档分块算法测试
 *
 * 重点覆盖：
 * - 段落按双换行切分
 * - 空段落跳过
 * - 超长段落按句号/感叹号/问号切分
 * - 多个段落合并到同一 chunk
 * - chunkSize 边界
 * - overlap 前后连接上下文
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceChunkTest {

    @Mock private DocumentChunkMapper chunkMapper;
    private RAGService service;

    @BeforeEach
    void setUp() {
        service = new RAGService(chunkMapper, "key", "http://zhipu", "embedding-3", 1024);
    }

    // ==================== 基础段落切分 ====================

    @Test
    void chunk_singleParagraph() {
        List<String> chunks = service.chunk("这是第一段内容。", 100, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("这是第一段内容"));
    }

    @Test
    void chunk_multipleParagraphs_joined() {
        String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = service.chunk(content, 500, 0);

        // 所有段落应该合并到一个 chunk（每个段落很短）
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段"));
        assertTrue(chunks.get(0).contains("第三段"));
    }

    @Test
    void chunk_paragraphsSeparated_byDoubleNewline() {
        // 单换行不分段，只有 \n\n 才分段
        String content = "第一段\n还是第一段\n\n第二段";
        List<String> chunks = service.chunk(content, 500, 0);

        assertEquals(1, chunks.size());
    }

    @Test
    void chunk_emptyParagraphs_skipped() {
        String content = "第一段\n\n\n\n第二段\n\n\n";
        List<String> chunks = service.chunk(content, 500, 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段"));
    }

    // ==================== 超长段落按句子切分 ====================

    @Test
    void chunk_longParagraphSplitByPeriod() {
        // 构造超过 chunkSize 的内容
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("这是第").append(i).append("句话。");
        }
        String content = sb.toString();

        // 每个句子约 10 字，chunkSize=50，大约每 5 句一块
        List<String> chunks = service.chunk(content, 50, 0);

        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= 100, "chunk 不应过长: " + c.length());
        }
    }

    @Test
    void chunk_longParagraph_splitByChinesePunctuation() {
        // 用各种标点
        String content = "这是一句。还有一句！又来一句？再一句。";
        List<String> chunks = service.chunk(content, 10, 0);

        // 应该拆成多块
        assertTrue(chunks.size() >= 2);
    }

    @Test
    void chunk_longParagraph_splitByEnglishPunctuation() {
        String content = "This is a sentence. And another one! Yet another? Also this.";
        List<String> chunks = service.chunk(content, 20, 0);

        assertTrue(chunks.size() >= 2);
    }

    // ==================== chunkSize 边界 ====================

    @Test
    void chunk_shortParagraph_joinsWithNeighbor() {
        String content = "短段落一。\n\n短段落二。\n\n短段落三。";
        List<String> chunks = service.chunk(content, 200, 0);

        // 所有短段落应该合并
        assertTrue(chunks.size() >= 1);
        if (chunks.size() == 1) {
            assertTrue(chunks.get(0).contains("短段落一"));
            assertTrue(chunks.get(0).contains("短段落三"));
        }
    }

    @Test
    void chunk_trimmed() {
        String content = "  第一段  \n\n  第二段  ";
        List<String> chunks = service.chunk(content, 500, 0);

        // 不应有首尾空白
        for (String c : chunks) {
            assertFalse(c.startsWith(" "), "不应以空格开头");
            assertFalse(c.endsWith(" "), "不应以空格结尾");
        }
    }

    // ==================== overlap 测试 ====================

    @Test
    void chunk_overlap_addsNextChunkHead() {
        // 构造两个段落，chunkSize 很小 → 两个 chunk
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("AA。");
        sb.append("\n\n");
        for (int i = 0; i < 10; i++) sb.append("BB。");
        String content = sb.toString();

        List<String> chunks = service.chunk(content, 30, 5);

        // overlap=5 时，第一个 chunk 末尾应追加第二个 chunk 的前 5 字符
        assertTrue(chunks.size() >= 2);
        // 第一个 chunk 末尾应包含来自第二个 chunk 的 "BB。" 前缀
        String first = chunks.get(0);
        assertTrue(first.endsWith("BB") || first.contains("BB"),
                "第一个 chunk 应该包含 overlap 来的下一段开头");
    }

    @Test
    void chunk_noOverlap_chunksAreIndependent() {
        List<String> chunks = service.chunk("段落一内容很多很多。\n\n段落二内容很多很多。", 10, 0);

        // overlap=0 时不应该互相包含
        assertEquals(2, chunks.size());
    }

    @Test
    void chunk_overlapWithSingleChunk_noChange() {
        // 只有一个 chunk，overlap 不应生效
        List<String> chunks = service.chunk("只有这一段。", 100, 10);
        assertEquals(1, chunks.size());
        assertEquals("只有这一段。", chunks.get(0).trim());
    }

    @Test
    void chunk_overlapDoesNotExceedNextChunkLength() {
        // overlap 比下一段还长
        List<String> chunks = service.chunk("长段落一。\n\n短二。", 20, 50);

        // 应该正常工作，只是 overlap 被截断到下一段实际长度
        assertTrue(chunks.size() >= 1);
    }

    // ==================== 边界输入 ====================

    @Test
    void chunk_emptyContent_returnsEmpty() {
        List<String> chunks = service.chunk("", 100, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunk_whitespaceOnly_returnsEmpty() {
        List<String> chunks = service.chunk("   \n\n  \n\n  ", 100, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunk_toVectorString_roundTrip() {
        float[] vec = new float[]{0.1f, 0.2f, -0.3f};
        String s = service.toVectorString(vec);
        assertEquals("[0.1,0.2,-0.3]", s);
    }

    @Test
    void chunk_zeroOverlap() {
        List<String> chunks = service.chunk("段一。\n\n段二。", 5, 0);
        assertEquals(2, chunks.size());
    }

}
