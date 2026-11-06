package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** DESC order, newest first, as returned by ChatMapper.findMessages */
    private List<ChatMessage> history(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(0, message("user", "msg" + i));
        }
        return list;
    }

    @Test
    void needsSummaryOnlyAboveFifteenMessages() {
        assertFalse(contextManager.needsSummary(history(15)));
        assertTrue(contextManager.needsSummary(history(16)));
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(List.of()));
    }

    @Test
    void getOldMessagesReturnsOnlyMessagesOutsideRecentWindow() {
        List<ChatMessage> sixteen = history(16);
        List<ChatMessage> old = contextManager.getOldMessages(sixteen);
        assertEquals(6, old.size());
        // recent 10 are msg7..msg16, so old messages must be msg1..msg6 (oldest tail)
        assertEquals("msg1", old.get(5).getContent());
        assertEquals("msg6", old.get(0).getContent());

        assertTrue(contextManager.getOldMessages(history(10)).isEmpty());
        assertTrue(contextManager.getOldMessages(null).isEmpty());
    }

    @Test
    void buildContextIncludesRagSummaryAndRecentConversationInAscendingOrder() {
        List<ChatMessage> history = List.of(
            message("assistant", "third"),
            message("user", "second"),
            message("user", "first"));

        String ctx = contextManager.buildContext(history,
            List.of("chunk-a", "chunk-b"), "背景摘要");

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("chunk-a"));
        assertTrue(ctx.contains("chunk-b"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("背景摘要"));
        assertTrue(ctx.contains("【最近对话】"));

        int first = ctx.indexOf("user: first");
        int second = ctx.indexOf("user: second");
        int third = ctx.indexOf("assistant: third");
        assertTrue(first >= 0 && second >= 0 && third >= 0);
        assertTrue(first < second && second < third, "recent messages should be replayed oldest -> newest");
    }

    @Test
    void buildContextOnlyIncludesRecentTenMessages() {
        String ctx = contextManager.buildContext(history(16), null, null);

        // msg16 is the newest; msg7..msg16 are the recent window, so msg1..msg6 must not appear
        assertTrue(ctx.contains("msg16"));
        assertTrue(ctx.contains("msg7"));
        assertFalse(ctx.contains("user: msg1\n"));
    }

    @Test
    void buildContextWithNoDataReturnsEmptyString() {
        assertEquals("", contextManager.buildContext(null, null, null));
        assertEquals("", contextManager.buildContext(List.of(), List.of(), ""));
    }

    @Test
    void buildContextTruncatesOversizedContext() {
        String huge = "x".repeat(50_001);
        String ctx = contextManager.buildContext(
            List.of(message("user", huge)), null, null);

        assertTrue(ctx.contains("...(上下文过长已截断)"));
        assertTrue(ctx.length() <= 50_000 + "...\n...(上下文过长已截断)".length());
    }
}
