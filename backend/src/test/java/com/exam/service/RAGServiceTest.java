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
        // 使用反射绕过 @Value 注入，直接构造对象
        ragService = new RAGService(chunkMapper, "test-key", "http://localhost", "embedding-model", 1024);
    }

    // ==================== chunk() 分块算法测试 ====================

    @Test
    void chunk_emptyContent_returnsEmpty() {
        List<String> chunks = ragService.chunk("", 100, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunk_onlyWhitespace_returnsEmpty() {
        List<String> chunks = ragService.chunk("\n\n\n   \n\n", 100, 0);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunk_singleShortParagraph_returnsSingleChunk() {
        String content = "这是一个简短的段落。";
        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(1, chunks.size());
        assertEquals("这是一个简短的段落。", chunks.get(0));
    }

    @Test
    void chunk_multipleShortParagraphs_fitsInOneChunk() {
        String content = "第一段。\n\n第二段。\n\n第三段。";
        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段。"));
        assertTrue(chunks.get(0).contains("第二段。"));
        assertTrue(chunks.get(0).contains("第三段。"));
    }

    @Test
    void chunk_multipleParagraphs_splitWhenExceedsChunkSize() {
        // 每段 ~30 字符，chunkSize=50，每段单独放不下两块
        String p1 = "A".repeat(30);
        String p2 = "B".repeat(30);
        String p3 = "C".repeat(30);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = ragService.chunk(content, 50, 0);

        // 应该切分成 3 块，每块单独一个段落
        assertEquals(3, chunks.size());
        assertEquals(p1, chunks.get(0));
        assertEquals(p2, chunks.get(1));
        assertEquals(p3, chunks.get(2));
    }

    @Test
    void chunk_veryLongParagraph_splitBySentences() {
        // 构造一个超长段落（10句，每句20字符）
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            para.append("第").append(i).append("句话内容ABCDEFGHIJK。");
        }
        String content = para.toString();

        // chunkSize=60，每句 ~22 字符 → 每块最多 2 句
        List<String> chunks = ragService.chunk(content, 60, 0);

        // 不应只有 1 块
        assertTrue(chunks.size() > 1);
        // 所有 chunks 拼接后应包含原文所有句子
        String all = String.join("", chunks);
        for (int i = 0; i < 10; i++) {
            assertTrue(all.contains("第" + i + "句话"),
                "Chunk should contain sentence " + i);
        }
    }

    @Test
    void chunk_sentenceSplitsOnChinesePunctuation() {
        // 确保中文句号、感叹号、问号都能切句
        String longPara = "这是第一句。然后是第二句！还有第三句？继续第四句。";
        String content = longPara;

        List<String> chunks = ragService.chunk(content, 15, 0);

        assertTrue(chunks.size() >= 2);
    }

    @Test
    void chunk_sentenceSplitsOnEnglishPunctuation() {
        String longPara = "Sentence one. Sentence two! Sentence three? Sentence four.";
        List<String> chunks = ragService.chunk(longPara, 20, 0);

        assertTrue(chunks.size() >= 2);
    }

    @Test
    void chunk_withOverlap_appendsNextChunkBeginning() {
        String p1 = "A".repeat(20);
        String p2 = "B".repeat(20);
        String p3 = "C".repeat(20);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        // chunkSize 很小，确保每段一个块；overlap = 5
        List<String> chunks = ragService.chunk(content, 25, 5);

        assertEquals(3, chunks.size());
        // 第 0 块末尾追加第 1 块开头 5 个字符
        assertTrue(chunks.get(0).endsWith("\n\n" + "B".repeat(5)),
            "First chunk should have overlap from second. Got: " + chunks.get(0));
        // 第 1 块末尾追加第 2 块开头
        assertTrue(chunks.get(1).endsWith("\n\n" + "C".repeat(5)),
            "Second chunk should have overlap from third. Got: " + chunks.get(1));
        // 最后一块没有 overlap
        assertEquals(p3, chunks.get(2),
            "Last chunk should have no overlap. Got: " + chunks.get(2));
    }

    @Test
    void chunk_overlapLargerThanNextChunk_takesAll() {
        String p1 = "A".repeat(10);
        String p2 = "B".repeat(5); // 短于 overlap
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 15, 100);

        assertEquals(2, chunks.size());
        // overlap 取 min(100, 5) = 全部
        assertTrue(chunks.get(0).endsWith("\n\n" + p2));
    }

    @Test
    void chunk_zeroOverlap_noAppend() {
        String p1 = "A".repeat(20);
        String p2 = "B".repeat(20);
        String content = p1 + "\n\n" + p2;

        List<String> chunks = ragService.chunk(content, 25, 0);

        assertEquals(2, chunks.size());
        assertEquals(p1, chunks.get(0));
        assertEquals(p2, chunks.get(1));
    }

    @Test
    void chunk_singleChunk_noOverlapApplied() {
        String content = "只有一段。不管 overlap 多大，都不应该追加。";
        List<String> chunks = ragService.chunk(content, 100, 50);

        assertEquals(1, chunks.size());
        assertEquals("只有一段。不管 overlap 多大，都不应该追加。", chunks.get(0));
    }

    @Test
    void chunk_preservesParagraphOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append("第").append(i).append("段内容").append("X".repeat(20)).append("\n\n");
        }
        String content = sb.toString().trim();

        List<String> chunks = ragService.chunk(content, 50, 0);

        // 检查顺序：第 0 段的内容应该出现在第 i 段之前
        for (int i = 1; i < 8; i++) {
            String prevTag = "第" + (i - 1) + "段";
            String currTag = "第" + i + "段";
            int prevIdx = -1, currIdx = -1;
            // 找到 prev 出现的第一个 chunk 位置
            for (int j = 0; j < chunks.size(); j++) {
                if (chunks.get(j).contains(prevTag)) { prevIdx = j; break; }
            }
            for (int j = 0; j < chunks.size(); j++) {
                if (chunks.get(j).contains(currTag)) { currIdx = j; break; }
            }
            assertTrue(prevIdx <= currIdx,
                "Paragraph " + (i-1) + " should appear before or same chunk as " + i);
        }
    }

    @Test
    void chunk_trimsEmptyParagraphs() {
        String content = "\n\n\n第一段。\n\n\n\n第二段。\n\n";
        List<String> chunks = ragService.chunk(content, 100, 0);

        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).startsWith("\n"));
        assertTrue(chunks.get(0).contains("第一段。"));
        assertTrue(chunks.get(0).contains("第二段。"));
    }

    @Test
    void chunk_chunksTrimmed() {
        String p1 = "   前面有空格   \n\n   后面也有   ";
        List<String> chunks = ragService.chunk(p1, 100, 0);

        // 段落 trim 了
        for (String c : chunks) {
            assertFalse(c.startsWith("   "), "Chunk should be trimmed: " + c);
            assertFalse(c.endsWith("   "), "Chunk should be trimmed: " + c);
        }
    }

    // ==================== toVectorString ====================

    @Test
    void toVectorString_emptyArray_formatsCorrectly() {
        assertEquals("[]", ragService.toVectorString(new float[0]));
    }

    @Test
    void toVectorString_singleElement() {
        assertEquals("[1.5]", ragService.toVectorString(new float[]{1.5f}));
    }

    @Test
    void toVectorString_multipleElements_commasSeparate() {
        String result = ragService.toVectorString(new float[]{1.0f, -2.5f, 3.14f});
        assertEquals("[1.0,-2.5,3.14]", result);
    }

    @Test
    void toVectorString_zeroValue() {
        assertEquals("[0.0]", ragService.toVectorString(new float[]{0.0f}));
    }

    // ==================== extractStructure 边界测试（返回空值的 mock 场景） ====================

    @Test
    void extractStructure_noChunks_returnsEmpty() {
        when(chunkMapper.getAllChunks("doc-empty")).thenReturn(List.of());
        String result = ragService.extractStructure("doc-empty");
        assertEquals("", result);
    }

    @Test
    void searchByDocument_exceptionReturnsEmpty() {
        // RAGService 会吞掉 embed 的异常返回空 List（因为 zhipu key 是假的）
        List<String> result = ragService.searchByDocument("doc1", "query", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchAll_exceptionReturnsEmpty() {
        List<String> result = ragService.searchAll("query", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void getChunksByIndex_exceptionReturnsEmpty() {
        when(chunkMapper.getChunksByIndex("docX", 10)).thenThrow(new RuntimeException("db error"));
        List<String> result = ragService.getChunksByIndex("docX", 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void getChunksUniform_exceptionReturnsEmpty() {
        when(chunkMapper.getChunksUniform("docX", 3)).thenThrow(new RuntimeException("db error"));
        List<String> result = ragService.getChunksUniform("docX", 3);
        assertTrue(result.isEmpty());
    }
}
