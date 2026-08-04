package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager cm = new ContextManager();

    // ===== needsSummary threshold =====

    @Test
    void needsSummaryFalseWhenNull() {
        assertFalse(cm.needsSummary(null));
    }

    @Test
    void needsSummaryFalseWhenFewMessages() {
        // RECENT_COUNT=10, threshold=RECENT_COUNT+5=15
        assertFalse(cm.needsSummary(List.of()));
        assertFalse(cm.needsSummary(makeMessages(10)));
        assertFalse(cm.needsSummary(makeMessages(15)));
    }

    @Test
    void needsSummaryTrueWhenExceedsThreshold() {
        // 16 > 15
        assertTrue(cm.needsSummary(makeMessages(16)));
        assertTrue(cm.needsSummary(makeMessages(100)));
    }

    // ===== getOldMessages slicing =====

    @Test
    void getOldMessagesEmptyForSmallHistory() {
        assertTrue(cm.getOldMessages(null).isEmpty());
        assertTrue(cm.getOldMessages(List.of()).isEmpty());
        assertTrue(cm.getOldMessages(makeMessages(5)).isEmpty());
        assertTrue(cm.getOldMessages(makeMessages(10)).isEmpty());
    }

    @Test
    void getOldMessagesReturnsBeyondRecent() {
        // 20 messages total, first 10 are "recent" (index 0..9), rest are "old" (10..19)
        List<ChatMessage> msgs = makeMessages(20);
        List<ChatMessage> old = cm.getOldMessages(msgs);
        assertEquals(10, old.size());
        assertEquals("msg-10", old.get(0).getContent());
        assertEquals("msg-19", old.get(old.size() - 1).getContent());
    }

    // ===== buildContext structure and ordering =====

    @Test
    void buildContextEmptyInputsProducesEmptyString() {
        String ctx = cm.buildContext(null, null, null);
        assertTrue(ctx.isEmpty());
    }

    @Test
    void buildContextIncludesRagChunks() {
        String ctx = cm.buildContext(null, List.of("chunk-A", "chunk-B"), null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· chunk-A"));
        assertTrue(ctx.contains("· chunk-B"));
    }

    @Test
    void buildContextIncludesSummary() {
        String ctx = cm.buildContext(null, List.of(), "old summary content");
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("old summary content"));
    }

    @Test
    void buildContextRecentMessagesOrderedAscending() {
        // history is DESC order (index 0 = latest) per ContextManager contract
        List<ChatMessage> hist = new ArrayList<>();
        hist.add(msg("assistant", "reply-B")); // latest (index 0)
        hist.add(msg("user", "question-B"));   // (index 1)
        hist.add(msg("assistant", "reply-A")); // (index 2)
        hist.add(msg("user", "question-A"));   // oldest (index 3)

        String ctx = cm.buildContext(hist, List.of(), null);
        assertTrue(ctx.contains("【最近对话】"));

        // ASC order means question-A → reply-A → question-B → reply-B
        int qA = ctx.indexOf("question-A");
        int rA = ctx.indexOf("reply-A");
        int qB = ctx.indexOf("question-B");
        int rB = ctx.indexOf("reply-B");
        assertTrue(qA < rA && rA < qB && qB < rB, "Messages must be in ascending chronological order");
    }

    @Test
    void buildContextTrimsLargeHistoryToRecent() {
        // 30 messages total; only first 10 (DESC latest) should appear
        List<ChatMessage> hist = makeMessages(30);
        String ctx = cm.buildContext(hist, List.of(), null);

        // Latest messages (0..9) should be present
        for (int i = 0; i < 10; i++) assertTrue(ctx.contains("msg-" + i), "msg-" + i + " should be in context");
        // Old messages (20..29) should NOT be present
        for (int i = 20; i < 30; i++) assertFalse(ctx.contains("msg-" + i), "msg-" + i + " should be trimmed");
    }

    @Test
    void buildContextFullAssemblyAllSections() {
        List<ChatMessage> hist = List.of(msg("user", "hello"));
        List<String> rag = List.of("knowledge 1");
        String summary = "earlier discussion";

        String ctx = cm.buildContext(hist, rag, summary);

        assertTrue(ctx.contains("【相关知识】"), "RAG section missing");
        assertTrue(ctx.contains("【对话背景】"), "Summary section missing");
        assertTrue(ctx.contains("【最近对话】"), "Recent section missing");
    }

    // ===== trimToTokens (via very long context) =====

    @Test
    void buildContextTruncatesOversizedContent() {
        // Build context with a chunk that exceeds MAX_TOKENS (50000)
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 60000) huge.append("X");
        String ctx = cm.buildContext(null, List.of(huge.toString()), null);
        assertTrue(ctx.contains("(上下文过长已截断)"));
        assertTrue(ctx.length() <= 50000 + 50, "Truncated output should not exceed max + trailer");
    }

    // ===== helpers =====

    private List<ChatMessage> makeMessages(int n) {
        List<ChatMessage> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ChatMessage m = new ChatMessage();
            m.setId("m" + i);
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("msg-" + i);
            m.setCreatedAt(LocalDateTime.now());
            list.add(m);
        }
        return list;
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
