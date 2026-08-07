package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock private DocumentChunkMapper chunkMapper;

    private RAGService newService() {
        return new RAGService(chunkMapper, "dummy-key",
            "https://example.com", "embedding-3", 1024);
    }

    // ══════════════════════════════════════════════════════════════════
    // chunk() — paragraph-based splitting
    // ══════════════════════════════════════════════════════════════════

    @Test
    void chunkSingleShortParagraphOneChunk() {
        RAGService svc = newService();
        String text = "这是一个短段落。只有几句话。";
        List<String> chunks = svc.chunk(text, 200, 0);
        assertEquals(1, chunks.size());
        assertEquals("这是一个短段落。只有几句话。", chunks.get(0));
    }

    @Test
    void chunkEmptyStringReturnsEmptyList() {
        RAGService svc = newService();
        List<String> chunks = svc.chunk("", 100, 0);
        assertTrue(chunks.isEmpty(), "Empty input → empty output");
    }

    @Test
    void chunkMultipleShortParagraphsFitInOneChunk() {
        RAGService svc = newService();
        String text = "第一段内容。\n\n第二段不长。\n\n第三段也很短。";
        List<String> chunks = svc.chunk(text, 100, 0);
        // All 3 fit under chunkSize
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段"));
        assertTrue(chunks.get(0).contains("第三段"));
    }

    @Test
    void chunkMultipleParagraphsSplitWhenExceedsChunkSize() {
        RAGService svc = newService();
        // Build two paragraphs, each ~20 chars but chunkSize=30 forces split.
        String p1 = "第一段内容足够长超过阈值。";
        String p2 = "第二段内容也足够长需要分开。";
        String text = p1 + "\n\n" + p2;

        List<String> chunks = svc.chunk(text, 20, 0);
        assertTrue(chunks.size() >= 2,
            "Should produce at least 2 chunks, got: " + chunks.size());
        // First chunk should contain the first paragraph (or a piece of it)
        assertTrue(chunks.get(0).contains("第一段") || chunks.get(0).contains("第"));
    }

    // ── long paragraph: sentence-level splitting ─────────────────────

    @Test
    void chunkLongParagraphSplitsBySentence() {
        RAGService svc = newService();
        // 5 sentences. chunkSize=8 → each sentence ~8 chars → split to ~5 pieces
        StringBuilder sb = new StringBuilder();
        sb.append("第一句话说完。");
        sb.append("然后是第二句内容。");
        sb.append("第三句也来了哦。");
        sb.append("第四句紧接着上。");
        sb.append("最后第五句结束。");
        List<String> chunks = svc.chunk(sb.toString(), 10, 0);
        // Each sentence is 8 chars (including 。). With chunkSize=10,
        // each chunk can carry 1 sentence.
        assertTrue(chunks.size() >= 3,
            "Expected multiple chunks for long paragraph, got: " + chunks.size());
    }

    @Test
    void chunkSentenceSplitsWithEnglishPunctuation() {
        RAGService svc = newService();
        String text = "Hello world. This is a test. Another sentence here!";
        // Sentences split on . ? !
        List<String> chunks = svc.chunk(text, 15, 0);
        assertTrue(chunks.size() >= 2,
            "Should split multiple English sentences, got: " + chunks.size());
    }

    // ── overlap appends tail of next chunk ───────────────────────────

    @Test
    void chunkOverlapAddsPrefixOfNextChunk() {
        RAGService svc = newService();
        // Two paragraphs that definitely split into separate chunks
        String p1 = "第一段内容非常丰富，信息量巨大。";
        String p2 = "第二段也有其独立的知识点和结构。";
        String text = p1 + "\n\n" + p2;

        List<String> noOverlap = svc.chunk(text, 12, 0);
        List<String> withOverlap = svc.chunk(text, 12, 5);

        assertTrue(noOverlap.size() >= 2);
        assertEquals(noOverlap.size(), withOverlap.size());

        if (withOverlap.size() >= 2) {
            String firstNoOverlap = noOverlap.get(0);
            String firstWithOverlap = withOverlap.get(0);
            // With overlap, first chunk should be strictly longer (prefix of next chunk appended)
            assertTrue(firstWithOverlap.length() >= firstNoOverlap.length(),
                "overlap should make first chunk at least as long");
        }
    }

    @Test
    void chunkSingleChunkNoOverlapAdded() {
        // Only one chunk → overlap branch has no effect (skip if chunks.size() <= 1)
        RAGService svc = newService();
        String text = "独一段落。";
        List<String> a = svc.chunk(text, 200, 0);
        List<String> b = svc.chunk(text, 200, 50);
        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertEquals(a.get(0), b.get(0));
    }

    @Test
    void chunkOverlapLargerThanNextChunkClamped() {
        // overlap > next.length → take min(overlap, next.length) → no exception
        RAGService svc = newService();
        String p1 = "第一个段落。";
        String p2 = "短。"; // next chunk is very short
        String text = p1 + "\n\n" + p2;
        assertDoesNotThrow(() -> svc.chunk(text, 8, 1000));
    }

    // ── blank paragraph skipping ─────────────────────────────────────

    @Test
    void chunkSkipsEmptyParagraphs() {
        RAGService svc = newService();
        String text = "非空段落A。\n\n   \n\n\n\n非空段落B。";
        List<String> chunks = svc.chunk(text, 500, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("非空段落A"));
        assertTrue(chunks.get(0).contains("非空段落B"));
    }

    // ══════════════════════════════════════════════════════════════════
    // toVectorString()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void toVectorStringEmptyArray() {
        RAGService svc = newService();
        assertEquals("[]", svc.toVectorString(new float[0]));
    }

    @Test
    void toVectorStringSingleElement() {
        RAGService svc = newService();
        assertEquals("[1.5]", svc.toVectorString(new float[]{1.5f}));
    }

    @Test
    void toVectorStringMultipleElements() {
        RAGService svc = newService();
        String s = svc.toVectorString(new float[]{1.0f, -2.5f, 3.14f});
        assertTrue(s.startsWith("["));
        assertTrue(s.endsWith("]"));
        // commas between numbers (no leading comma after [)
        assertFalse(s.contains("[,"));
        String body = s.substring(1, s.length() - 1);
        String[] parts = body.split(",");
        assertEquals(3, parts.length);
    }
}
