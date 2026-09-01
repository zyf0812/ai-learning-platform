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
 * RAGService 分块算法（chunk 方法）测试。
 *
 * chunk 是文档索引的第一步，算法缺陷会导致：
 *  - 切分位置不对 -> 句子语义断裂 -> Embedding 质量下降 -> 检索不准
 *  - overlap 失效 -> 上下文边缘丢失 -> 跨块知识点查不到
 *  - 超长段落未切句 -> 单块过大 -> token 超限
 *
 * chunk 是纯算法方法，不依赖外部服务，适合做确定性单元测试。
 * 使用 Reflection 或 直接调用实例方法（需绕过 @Value 构造参数）。
 *
 * 由于 chunk 是 public 实例方法，我们通过以下方式绕过 @Value：
 *  构造参数均为基本类型，chunk 方法内部不使用它们，因此传 null/占位值即可，
 *  但 chunkMapper 不被 chunk() 方法使用（不传 null 会有风险吗？看代码 - chunk() 完全不用）。
 *  实际上 chunk() 方法只使用参数，不使用字段。所以可以安全地直接 new。
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceChunkTest {

    @Mock private DocumentChunkMapper chunkMapper;

    private RAGService rag;

    @BeforeEach
    void setUp() {
        // chunk() 方法不使用任何注入字段，只依赖方法参数和局部算法。
        // 因此 @Value 的外部依赖可以传占位值。
        rag = new RAGService(chunkMapper, "dummy-key", "http://localhost", "embedding-2", 1024);
    }

    // ========== 基本段落切分 ==========

    @Test
    void chunk_splitsByDoubleNewline_paragraphs() {
        // 每个段落长度 12 左右，chunkSize=14 略大于单段 (<14+空行+下一段 就会触发拆分)
        // 这样才能强制每段独立成块（合并后超过 chunkSize 就拆）
        String content = "第一段内容测试。\n\n第二段内容测试。\n\n第三段内容测试。";

        List<String> chunks = rag.chunk(content, 14, 0);

        assertEquals(3, chunks.size(), "3 个段落应因 chunkSize(14) 限制切成 3 块，实际: " + chunks);
        assertEquals("第一段内容测试。", chunks.get(0));
        assertEquals("第二段内容测试。", chunks.get(1));
        assertEquals("第三段内容测试。", chunks.get(2));
    }

    @Test
    void chunk_ignoresEmptyParagraphs_betweenSeparators() {
        // chunkSize=7：A段5字 + 空行(2) = 7 刚好，若加 B 段 >7 则拆
        // 但实际上算法: current += para + "\n\n", 判断在下一个 para 之前
        //   所以用 chunkSize=4 能强制拆开：A(5) 不超过 4 但 para(5) 超过 chunkSize ->
        //   para.length() > chunkSize 分支，先 flush current(空), 然后按句切
        //   (A段无标点，不会切句，直接 current += para+空行，current=5+2=7)
        //   B段 9 字，para(9)>4，flush current("A 段。"), 然后 B 添加
        //   C 段同理 flush B
        // 最终 3 chunks
        String content = "A 段。\n\n\n\nB 段测试内容。\n\n   \n\nC 段测试内容。";

        List<String> chunks = rag.chunk(content, 4, 0);

        assertEquals(3, chunks.size(), "chunkSize=4 应强制拆出 3 段。实际: " + chunks);
        // 注意：由于按句切后 "A 段。" 内部无句号拆分字符，所以整段被加入
        assertEquals("A 段。", chunks.get(0));
    }

    @Test
    void chunk_shortParagraphsCombinedWithinChunkSize() {
        // 多个短段落（每段 6 字），chunkSize=200，应合并
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            sb.append("第").append(i).append("段短内容。\n\n");
        }
        String content = sb.toString();

        List<String> chunks = rag.chunk(content, 200, 0);

        assertEquals(1, chunks.size(),
            "6 个短段落 + 大 chunkSize -> 合并为 1 块。实际块数: " + chunks.size());
        assertTrue(chunks.get(0).contains("\n\n"), "合并块中应保留段落分隔空行");
    }

    @Test
    void chunk_singleLongParagraph_noDoubleNewline_stillProducesOneChunk() {
        // 一段没有任何双换行的中等长度文本
        String content = "这是一个单段落文本。它不包含任何空行，但它的长度小于 chunkSize，所以应该完整地作为一个 chunk 返回，而不是被错误地切碎。";

        List<String> chunks = rag.chunk(content, 500, 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsWith("这是一个单段落文本"));
    }

    // ========== 超长段落按句子切分 ==========

    @Test
    void chunk_veryLongParagraph_splitsBySentence() {
        // 构造一个长度 > chunkSize 的单段落，段落内由多个中文句号句子组成
        int sentenceCount = 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= sentenceCount; i++) {
            sb.append("第").append(i).append("句：这是一段较长的测试句子内容，用来模拟长段落的句子切分。");
        }
        String content = sb.toString();
        assertTrue(content.length() > 100, "确保输入确实很长");

        // chunkSize=150 远小于总长度，但足以容纳几句话
        List<String> chunks = rag.chunk(content, 150, 0);

        assertTrue(chunks.size() >= 2,
            "超长段落必须按句子被拆分，实际块数: " + chunks.size() + "，内容总长度: " + content.length());

        // 每块都不能为空
        for (String c : chunks) {
            assertFalse(c.isEmpty(), "块不应为空");
        }
    }

    @Test
    void chunk_longParagraph_mixedEnglishPunctuation_splitsCorrectly() {
        // 混合使用中英文句号。英文句号也应触发句子切分
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            if (i % 2 == 0) sb.append("English sentence number ").append(i).append(". ");
            else sb.append("中文句子编号").append(i).append("。");
        }
        String content = sb.toString();

        List<String> chunks = rag.chunk(content, 200, 0);

        assertTrue(chunks.size() >= 2,
            "中英文混合句号的长段落也应被拆分，实际块数: " + chunks.size());
    }

    @Test
    void chunk_sentenceSplit_doesNotLoseContent() {
        // 切分后的所有块拼接起来，应包含原内容的所有句子编号信息
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            sb.append("【句子编号").append(i).append("】这是用于验证内容完整性的标记语句。");
        }
        String content = sb.toString();

        List<String> chunks = rag.chunk(content, 120, 0);
        String merged = String.join("", chunks);

        for (int i = 1; i <= 8; i++) {
            assertTrue(merged.contains("【句子编号" + i + "】"),
                "内容不应丢失：缺少句子 " + i + "。合并后: " + merged);
        }
    }

    // ========== overlap 上下文连贯 ==========

    @Test
    void chunk_overlapAppendsStartOfNextChunk() {
        // chunkSize=14 限制每段 13-14 字自成一块，共 4 块
        String p1 = "AAAA段落一内容测试。";  // 12 字
        String p2 = "BBBB段落二内容测试。";  // 12 字
        String p3 = "CCCC段落三内容测试。";  // 12 字
        String p4 = "DDDD段落四内容测试。";  // 12 字
        String content = p1 + "\n\n" + p2 + "\n\n" + p3 + "\n\n" + p4;

        List<String> chunks = rag.chunk(content, 14, 5); // overlap=5 字符

        assertEquals(4, chunks.size(), "小 chunkSize 下 4 个段落应切成 4 块。实际: " + chunks);
        // 第 1 块: p1 本身 + 空行 + p2 前 5 字符
        assertTrue(chunks.get(0).startsWith(p1), "chunk 0 应以 p1 开头。实际: " + chunks.get(0));
        assertTrue(chunks.get(0).endsWith("\n\n" + p2.substring(0, 5)),
            "chunk 0 尾部应追加 p2 前 5 字符 (overlap=5)。实际: " + chunks.get(0));
        // 最后一块只有 p4，不应追加内容
        assertEquals(p4, chunks.get(3), "最后一块应等于 p4，不应含任何追加");
    }

    @Test
    void chunk_overlapZero_noAppendedText() {
        String p1 = "P1 内容测试。";
        String p2 = "P2 内容测试。";
        String p3 = "P3 内容测试。";
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = rag.chunk(content, 14, 0);

        assertEquals(3, chunks.size(), "小 chunkSize 下 3 段应拆成 3 块");
        assertEquals(p1, chunks.get(0),
            "overlap=0 时不应追加下一块内容");
        assertEquals(p2, chunks.get(1));
        assertEquals(p3, chunks.get(2));
    }

    @Test
    void chunk_overlapLargerThanNextChunk_takeTheWholeNext() {
        // chunkSize=10: p1(10字)边界 -> 下一段 p2 触发 current flush
        String p1 = "P1 长内容测试。";  // 10 字
        String p2 = "P2。";            // 3 字
        String content = p1 + "\n\n" + p2;
        List<String> chunks = rag.chunk(content, 10, 100);

        assertEquals(2, chunks.size(), "应拆成 2 块。实际: " + chunks);
        // overlap 100 > p2.length()(3), 应追加 p2 整个到 chunk 0 尾
        assertTrue(chunks.get(0).endsWith("\n\n" + p2),
            "overlap 超过下一块时应追加其全部。chunk 0= " + chunks.get(0));
    }

    @Test
    void chunk_onlyOneChunk_noOverlapSegment() {
        // 只有一块，overlap 不应产生任何额外内容（size <= 1 分支）
        String content = "只有一个段落";
        List<String> chunks = rag.chunk(content, 500, 50);
        assertEquals(1, chunks.size());
        assertEquals("只有一个段落", chunks.get(0));
    }

    // ========== 边界 / 空输入 ==========

    @Test
    void chunk_emptyString_returnsEmptyList() {
        List<String> chunks = rag.chunk("", 500, 0);
        assertTrue(chunks.isEmpty(), "空字符串应返回空块列表");
    }

    @Test
    void chunk_whitespaceOnly_returnsEmptyList() {
        List<String> chunks = rag.chunk("   \n\n  \n\t\n  ", 500, 0);
        assertTrue(chunks.isEmpty(), "全空白内容应返回空块列表");
    }

    @Test
    void chunk_singleShortParagraph_noSplitNoOverlap() {
        String content = "单独一段短文。";
        List<String> chunks = rag.chunk(content, 500, 10);
        assertEquals(List.of("单独一段短文。"), chunks);
    }

    // ========== toVectorString 格式 ==========

    @Test
    void toVectorString_formatsBracketCommaSeparated() {
        float[] vec = {1.0f, -0.5f, 0.25f};
        String s = rag.toVectorString(vec);
        assertTrue(s.startsWith("["));
        assertTrue(s.endsWith("]"));
        assertTrue(s.contains("1.0") && s.contains("-0.5") && s.contains("0.25"));
        // 不含任何多余逗号
        String core = s.substring(1, s.length() - 1);
        assertEquals(2, core.chars().filter(c -> c == ',').count(),
            "3 个元素之间应有恰好 2 个逗号。核心: " + core);
    }

    @Test
    void toVectorString_emptyArray_returnsEmptyBrackets() {
        assertEquals("[]", rag.toVectorString(new float[0]));
    }
}
