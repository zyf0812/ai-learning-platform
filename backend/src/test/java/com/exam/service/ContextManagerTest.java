package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(java.util.UUID.randomUUID().toString().substring(0, 8));
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // ---------- needsSummary threshold ----------

    @Test
    void needsSummaryFalseWhenNullHistory() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void needsSummaryFalseWhenFewMessages() {
        // RECENT_COUNT = 10, threshold = RECENT_COUNT + 5 = 15
        // So <= 15 messages don't need summary
        List<ChatMessage> h14 = new ArrayList<>();
        for (int i = 0; i < 15; i++) h14.add(msg("user", "hi" + i));
        assertFalse(manager.needsSummary(h14));
    }

    @Test
    void needsSummaryTrueWhenAboveThreshold() {
        // 16 messages -> need summary
        List<ChatMessage> h = new ArrayList<>();
        for (int i = 0; i < 16; i++) h.add(msg("user", "m" + i));
        assertTrue(manager.needsSummary(h));
    }

    // ---------- getOldMessages slicing ----------

    @Test
    void getOldMessagesReturnsEmptyForNull() {
        assertEquals(List.of(), manager.getOldMessages(null));
    }

    @Test
    void getOldMessagesReturnsEmptyWhenHistorySmall() {
        List<ChatMessage> h = new ArrayList<>();
        for (int i = 0; i < 10; i++) h.add(msg("user", "m" + i));
        assertEquals(List.of(), manager.getOldMessages(h));
    }

    @Test
    void getOldMessagesReturnsMessagesBeyondRecent() {
        // history is DESC order (newest first, per comment in ContextManager)
        List<ChatMessage> h = new ArrayList<>();
        // 15 messages total. h[0] = newest, h[14] = oldest.
        // getOldMessages should return h.subList(10, 15) i.e. indices 10..14 (the 5 oldest)
        for (int i = 0; i < 15; i++) {
            h.add(msg("user", "msg-" + i));  // msg-0 = newest, msg-14 = oldest
        }
        List<ChatMessage> old = manager.getOldMessages(h);
        assertEquals(5, old.size());
        // oldest messages (last in list) should be first in old
        assertEquals("msg-10", old.get(0).getContent());
        assertEquals("msg-14", old.get(old.size() - 1).getContent());
    }

    // ---------- buildContext assembly ----------

    @Test
    void buildContextWithRagChunksAddsKnowledgeSection() {
        List<String> chunks = List.of("chunk1 content", "chunk2 more");
        String ctx = manager.buildContext(List.of(), chunks, null);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("chunk1 content"));
        assertTrue(ctx.contains("chunk2 more"));
        assertTrue(ctx.contains("· "));
    }

    @Test
    void buildContextWithSummaryAddsBackgroundSection() {
        String ctx = manager.buildContext(List.of(), List.of(), "之前聊了A和B的对比");

        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("之前聊了A和B的对比"));
    }

    @Test
    void buildContextWithHistoryAddsRecentSectionInAscOrder() {
        // History is DESC order (newest first): user->msg3, assistant->msg2, user->msg1
        List<ChatMessage> h = List.of(
            msg("user", "msg3"),       // newest
            msg("assistant", "msg2"),
            msg("user", "msg1")        // oldest
        );
        String ctx = manager.buildContext(h, List.of(), null);

        assertTrue(ctx.contains("【最近对话】"));
        // Should be in ASC order: msg1, msg2, msg3
        int idx1 = ctx.indexOf("user: msg1");
        int idx2 = ctx.indexOf("assistant: msg2");
        int idx3 = ctx.indexOf("user: msg3");
        assertTrue(idx1 >= 0 && idx2 >= 0 && idx3 >= 0);
        assertTrue(idx1 < idx2 && idx2 < idx3, "Messages should be in ASC chronological order");
    }

    @Test
    void buildContextHistoryLimitedToRecentCount() {
        // 30 messages (>> RECENT_COUNT=10), only 10 most recent should appear
        List<ChatMessage> h = new ArrayList<>();
        for (int i = 0; i < 30; i++) h.add(msg("user", "m" + i));
        // h[0]=m0 newest? No: wait, h[0] is added first, then m1, m2... m29 is at h[29]
        // Wait actually we add msg(..., "m"+i) where i 0..29. So h[0]=m0, h[1]=m1, ..., h[29]=m29.
        // Per comment in ContextManager, history is DESC (newest first). So caller puts newest at h[0].
        // But in this test we're faking: need m29 as newest (index 0) ... m0 as oldest (index 29).
        // So reverse the list order so that m29 is at h[0] (newest) and m0 at h[29] (oldest)
        java.util.Collections.reverse(h);
        // Now h[0]=m29 (newest), h[29]=m0 (oldest)

        String ctx = manager.buildContext(h, List.of(), null);

        // History subList(0, 10) = m29..m20, then reversed -> m20..m29 in ASC display order.
        for (int i = 20; i < 30; i++) {
            assertTrue(ctx.contains("m" + i), "Should contain recent message m" + i);
        }
        // Old messages (index 10..29 in sublist check, i.e. m19..m0) should be excluded.
        // Because buildContext takes history.subList(0, 10) only (indices 0..9).
        // So m19 (at original index h[10]) won't appear.
        for (int i = 0; i < 20; i++) {
            // Use pattern like "user: m0\n" to avoid substring matches
            assertFalse(ctx.contains("user: m" + i + "\n"),
                "Old message m" + i + " should have been excluded from context");
        }
    }

    @Test
    void buildContextNullInputsDontCrash() {
        assertDoesNotThrow(() -> manager.buildContext(null, null, null));
    }

    @Test
    void buildContextAllSectionsPresent() {
        List<ChatMessage> h = List.of(msg("assistant", "最近回复"));
        List<String> chunks = List.of("知识片段");
        String ctx = manager.buildContext(h, chunks, "对话摘要内容");

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("知识片段"));
        assertTrue(ctx.contains("对话摘要内容"));
        assertTrue(ctx.contains("最近回复"));
    }

    // ---------- trimToTokens ----------

    @Test
    void buildContextShortTextNotTruncated() {
        List<ChatMessage> h = List.of(msg("user", "hi"));
        String ctx = manager.buildContext(h, List.of("short"), null);
        assertFalse(ctx.contains("...(上下文过长已截断)"));
    }

    @Test
    void buildContextLongTextGetsTruncated() {
        // Create text way over MAX_TOKENS (50000)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("very long chunk ").append(i).append(" ");
        List<String> bigChunks = List.of(sb.toString());
        String ctx = manager.buildContext(List.of(), bigChunks, null);
        assertTrue(ctx.contains("...(上下文过长已截断)"));
        assertTrue(ctx.length() <= 50000 + 50, "Should be truncated around 50000 chars");
    }

    @Test
    void buildContextEmptyInputs() {
        String ctx = manager.buildContext(List.of(), List.of(), "");
        assertNotNull(ctx);
    }
}
