package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private List<ChatMessage> descendingHistory(int n) {
        // The service treats history as DESC (newest first); mirror that here.
        List<ChatMessage> list = new ArrayList<>();
        for (int i = n; i >= 1; i--) {
            list.add(msg(i % 2 == 0 ? "assistant" : "user", "msg-" + i));
        }
        return list;
    }

    @Test
    void needsSummaryReturnsFalseWhenHistoryIsShort() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(List.of()));
        assertFalse(contextManager.needsSummary(descendingHistory(10)));
        assertFalse(contextManager.needsSummary(descendingHistory(15)));
    }

    @Test
    void needsSummaryReturnsTrueWhenHistoryExceedsThreshold() {
        // Threshold = RECENT_COUNT + 5 = 15
        assertTrue(contextManager.needsSummary(descendingHistory(16)));
    }

    @Test
    void getOldMessagesExcludesRecentWindow() {
        List<ChatMessage> history = descendingHistory(20);
        List<ChatMessage> old = contextManager.getOldMessages(history);
        // 10 most-recent are kept; the remaining 10 are considered "old".
        assertEquals(10, old.size());
    }

    @Test
    void getOldMessagesReturnsEmptyWhenNoOldMessages() {
        assertEquals(0, contextManager.getOldMessages(null).size());
        assertEquals(0, contextManager.getOldMessages(descendingHistory(10)).size());
        assertEquals(0, contextManager.getOldMessages(descendingHistory(15)).size());
    }

    @Test
    void buildContextIncludesRagChunksKnowledge() {
        String ctx = contextManager.buildContext(
            Collections.emptyList(),
            List.of("chapter 1 content", "chapter 2 content"),
            null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("chapter 1 content"));
        assertTrue(ctx.contains("chapter 2 content"));
    }

    @Test
    void buildContextIncludesSummarySection() {
        String ctx = contextManager.buildContext(
            Collections.emptyList(), null, "previous topic was X");
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("previous topic was X"));
    }

    @Test
    void buildContextReversesHistoryToAscendingOrder() {
        List<ChatMessage> history = descendingHistory(4);
        String ctx = contextManager.buildContext(history, null, null);
        // The newest entry ("msg-4") should appear AFTER msg-1 in the rendered context.
        int posMsg1 = ctx.indexOf("user: msg-1");
        int posMsg4 = ctx.indexOf("assistant: msg-4");
        assertTrue(posMsg1 >= 0);
        assertTrue(posMsg4 >= 0);
        assertTrue(posMsg1 < posMsg4,
            "ASC order expected: msg-1 (" + posMsg1 + ") should come before msg-4 (" + posMsg4 + ")");
    }

    @Test
    void buildContextTruncatesWhenOverTokenBudget() {
        // 60_000 chars of RAG chunks exceeds the 50_000 token budget; truncation marker must appear.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 60_000; i++) big.append('x');
        String ctx = contextManager.buildContext(
            Collections.emptyList(),
            List.of(big.toString()),
            null);
        assertTrue(ctx.endsWith("...(上下文过长已截断)"),
            "expected truncation marker at the end of context");
        // Sanity: result is bounded so downstream token usage stays predictable.
        assertTrue(ctx.length() <= 50_000 + "\n...(上下文过长已截断)".length());
    }

    @Test
    void buildContextHandlesNullRagChunksGracefully() {
        String ctx = contextManager.buildContext(descendingHistory(2), null, null);
        assertFalse(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextKeepsAllMessagesWhenBelowRecentWindow() {
        // With only 8 messages (≤ RECENT_COUNT) nothing should be considered "old".
        List<ChatMessage> history = descendingHistory(8);
        String ctx = contextManager.buildContext(history, null, null);
        for (int i = 1; i <= 8; i++) {
            assertTrue(ctx.contains("msg-" + i), "expected msg-" + i + " to appear");
        }
    }
}