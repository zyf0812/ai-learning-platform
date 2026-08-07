package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager mgr = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // ══════════════════════════════════════════════════════════════════
    // needsSummary()
    // threshold = RECENT_COUNT (10) + 5 = 15 messages
    // ══════════════════════════════════════════════════════════════════

    @Test
    void needsSummaryNullHistoryFalse() {
        assertFalse(mgr.needsSummary(null));
    }

    @Test
    void needsSummaryEmptyListFalse() {
        assertFalse(mgr.needsSummary(List.of()));
    }

    @Test
    void needsSummaryExactlyFifteenFalse() {
        // RECENT_COUNT=10, threshold = 10+5 = 15
        // size > 15 → true; size == 15 → false
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 15; i++) hist.add(msg("user", "m" + i));
        assertFalse(mgr.needsSummary(hist),
            "history of exactly 15 should NOT trigger summary");
    }

    @Test
    void needsSummarySixteenTrue() {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 16; i++) hist.add(msg("user", "m" + i));
        assertTrue(mgr.needsSummary(hist),
            "history of 16 should trigger summary");
    }

    @Test
    void needsSummaryTenMessagesFalse() {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 10; i++) hist.add(msg("user", "m" + i));
        assertFalse(mgr.needsSummary(hist));
    }

    // ══════════════════════════════════════════════════════════════════
    // getOldMessages() — returns messages beyond first RECENT_COUNT (10)
    // Note: history is DESC (newest first). subList(RECENT_COUNT, size)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(mgr.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessagesExactlyTenReturnsEmpty() {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 10; i++) hist.add(msg("user", "m" + i));
        assertTrue(mgr.getOldMessages(hist).isEmpty());
    }

    @Test
    void getOldMessagesTwelveReturnsTwoOldest() {
        List<ChatMessage> hist = new ArrayList<>();
        // hist[0] = newest ("m11"), hist[11] = oldest ("m0")
        for (int i = 0; i < 12; i++) hist.add(msg("user", "m" + i));
        List<ChatMessage> old = mgr.getOldMessages(hist);
        assertEquals(2, old.size());
        // Old messages are hist.subList(10, 12) = hist[10], hist[11]
        assertEquals("m10", old.get(0).getContent());
        assertEquals("m11", old.get(1).getContent());
    }

    // ══════════════════════════════════════════════════════════════════
    // buildContext()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void buildContextAllNullsReturnsNonEmptyString() {
        String ctx = mgr.buildContext(null, null, null);
        assertNotNull(ctx);
    }

    @Test
    void buildContextIncludesRagChunks() {
        List<String> chunks = List.of("Chunk A content", "Chunk B data");
        String ctx = mgr.buildContext(new ArrayList<>(), chunks, null);
        assertTrue(ctx.contains("【相关知识】"),
            "RAG section header missing");
        assertTrue(ctx.contains("Chunk A content"), "first chunk missing");
        assertTrue(ctx.contains("Chunk B content"), "second chunk missing");
    }

    @Test
    void buildContextIncludesSummary() {
        String ctx = mgr.buildContext(new ArrayList<>(), List.of(),
            "这是对话摘要，讨论了Spring框架和依赖注入。");
        assertTrue(ctx.contains("【对话背景】"),
            "summary section header missing");
        assertTrue(ctx.contains("Spring框架"));
    }

    @Test
    void buildContextIncludesRecentMessagesInAscOrder() {
        // history is DESC: index 0 = newest → should display oldest→newest
        List<ChatMessage> hist = new ArrayList<>();
        hist.add(msg("assistant", "C的回答")); // newest (index 0)
        hist.add(msg("user", "B的提问"));
        hist.add(msg("user", "A的提问"));     // oldest

        String ctx = mgr.buildContext(hist, List.of(), null);

        assertTrue(ctx.contains("【最近对话】"),
            "recent section header missing");
        // In ASC order, oldest user message appears first
        int idxA = ctx.indexOf("A的提问");
        int idxB = ctx.indexOf("B的提问");
        int idxC = ctx.indexOf("C的回答");
        assertTrue(idxA >= 0 && idxB >= 0 && idxC >= 0,
            "all three messages should appear");
        assertTrue(idxA < idxB && idxB < idxC,
            "order should be ASC: A < B < C, got positions: "
            + idxA + ", " + idxB + ", " + idxC);
    }

    @Test
    void buildContextLargeHistoryTrimsToRecentTen() {
        // 12 messages: 2 old + 10 recent. Only 10 recent (indices 0..9)
        // should be present in 【最近对话】
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 12; i++) hist.add(msg("user", "m" + i));
        String ctx = mgr.buildContext(hist, List.of(), null);
        // newest 10: m2..m11 are indices 0..9 of history
        assertTrue(ctx.contains("m2"), "most recent m2 missing");
        assertTrue(ctx.contains("m11"), "oldest of recent m11 missing");
        // indices 10, 11 of hist = m10, m11 → NO wait: indices 0..9 are RECENT
        // m0 m1 are beyond recent_count (old). They should NOT be shown.
        assertFalse(ctx.contains("user: m0\n"),
            "m0 is old, should not be in recent section");
        assertFalse(ctx.contains("user: m1\n"),
            "m1 is old, should not be in recent section");
    }

    @Test
    void buildContextCombinesAllSections() {
        List<ChatMessage> hist = List.of(msg("assistant", "嗨"));
        List<String> chunks = List.of("知识点数据");
        String summary = "之前聊了Java";
        String ctx = mgr.buildContext(hist, chunks, summary);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("知识点数据"));
        assertTrue(ctx.contains("之前聊了Java"));
        assertTrue(ctx.contains("assistant: 嗨"));
    }

    @Test
    void buildContextExtremelyLongGetsTruncated() {
        // Feed MAX_TOKENS (50000) * 2 worth of content → expect trim
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) huge.append("超级长文本内容".repeat(25));
        List<String> hugeChunks = List.of(huge.toString());
        String ctx = mgr.buildContext(new ArrayList<>(), hugeChunks, null);
        assertTrue(ctx.length() <= 50000 + "\n...(上下文过长已截断)".length() + 1,
            "context should be truncated at ~MAX_TOKENS chars");
        assertTrue(ctx.endsWith("...(上下文过长已截断)"),
            "very long context should end with truncation marker");
    }
}
