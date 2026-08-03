package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        contextManager = new ContextManager();
    }

    // ==================== needsSummary 测试 ====================

    @Test
    void needsSummaryWithNullHistoryReturnsFalse() {
        assertFalse(contextManager.needsSummary(null));
    }

    @Test
    void needsSummaryWithEmptyHistoryReturnsFalse() {
        assertFalse(contextManager.needsSummary(List.of()));
    }

    @Test
    void needsSummaryWithFewMessagesReturnsFalse() {
        // RECENT_COUNT=10, 阈值 = 10+5 = 15
        List<ChatMessage> msgs = generateMessages(15);
        assertFalse(contextManager.needsSummary(msgs),
            "15 messages should NOT need summary (threshold is > 15)");
    }

    @Test
    void needsSummaryWithManyMessagesReturnsTrue() {
        // > 15 条应触发摘要
        List<ChatMessage> msgs = generateMessages(16);
        assertTrue(contextManager.needsSummary(msgs));
    }

    @Test
    void needsSummaryBoundaryCheck() {
        assertFalse(contextManager.needsSummary(generateMessages(0)));
        assertFalse(contextManager.needsSummary(generateMessages(10)));
        assertFalse(contextManager.needsSummary(generateMessages(15)));
        assertTrue(contextManager.needsSummary(generateMessages(16)));
        assertTrue(contextManager.needsSummary(generateMessages(100)));
    }

    // ==================== getOldMessages 测试 ====================

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(contextManager.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessagesFewOrExactRecentReturnsEmpty() {
        assertTrue(contextManager.getOldMessages(generateMessages(0)).isEmpty());
        assertTrue(contextManager.getOldMessages(generateMessages(5)).isEmpty());
        assertTrue(contextManager.getOldMessages(generateMessages(10)).isEmpty());
    }

    @Test
    void getOldMessagesMoreThanRecentReturnsOldOnes() {
        List<ChatMessage> all = generateMessages(25);
        List<ChatMessage> old = contextManager.getOldMessages(all);

        // RECENT_COUNT=10，旧消息应为 25-10=15 条
        assertEquals(15, old.size());
        // 原始列表为 DESC，所以 subList(10,25) 应是较旧的消息
        assertEquals("msg-10", old.get(0).getContent());
        assertEquals("msg-24", old.get(old.size() - 1).getContent());
    }

    @Test
    void getOldMessagesCorrectSublistBoundaries() {
        List<ChatMessage> all = generateMessages(12);
        List<ChatMessage> old = contextManager.getOldMessages(all);
        assertEquals(2, old.size());
        assertEquals("msg-10", old.get(0).getContent());
        assertEquals("msg-11", old.get(1).getContent());
    }

    // ==================== buildContext 测试 ====================

    @Test
    void buildContextWithAllInputs() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("user", "问题"));
        history.add(msg("assistant", "回答"));

        List<String> ragChunks = List.of("知识点1", "知识点2");
        String summary = "之前讨论了A和B";

        String ctx = contextManager.buildContext(history, ragChunks, summary);

        assertTrue(ctx.contains("【相关知识】"), "Should include RAG header");
        assertTrue(ctx.contains("知识点1"), "Should include RAG content");
        assertTrue(ctx.contains("知识点2"), "Should include RAG content");
        assertTrue(ctx.contains("【对话背景】"), "Should include summary header");
        assertTrue(ctx.contains("之前讨论了A和B"), "Should include summary content");
        assertTrue(ctx.contains("【最近对话】"), "Should include recent conversation header");
        assertTrue(ctx.contains("user: 问题"), "Should include user message");
        assertTrue(ctx.contains("assistant: 回答"), "Should include assistant message");
    }

    @Test
    void buildContextWithOnlyRecentMessages() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("assistant", "回答"));   // DESC 最新在前
        history.add(msg("user", "问题"));

        String ctx = contextManager.buildContext(history, null, null);

        // 应反转成 ASC 顺序：先 user，后 assistant
        int userIdx = ctx.indexOf("user: 问题");
        int asstIdx = ctx.indexOf("assistant: 回答");
        assertTrue(userIdx >= 0 && asstIdx >= 0, "Both messages should appear");
        assertTrue(userIdx < asstIdx,
            "user message should come BEFORE assistant (ASC order), but uIdx=" + userIdx + ", aIdx=" + asstIdx);
    }

    @Test
    void buildContextTrimsToRecentOnly() {
        // 20 条历史，只取最近 10 条（前 10 条）
        List<ChatMessage> history = generateMessages(20);
        String ctx = contextManager.buildContext(history, null, null);

        // msg-0 到 msg-9 应在（最近的），msg-10 及之后不应出现
        for (int i = 0; i < 10; i++) {
            assertTrue(ctx.contains("msg-" + i), "msg-" + i + " should be in context");
        }
        assertFalse(ctx.contains("msg-10"), "msg-10 should NOT be in recent (trimmed away)");
    }

    @Test
    void buildContextWithoutOptionalInputs() {
        List<ChatMessage> history = List.of(msg("user", "hi"));

        String ctx = contextManager.buildContext(history, null, null);

        assertFalse(ctx.contains("【相关知识】"), "Should not include RAG header when no chunks");
        assertFalse(ctx.contains("【对话背景】"), "Should not include summary header when no summary");
        assertTrue(ctx.contains("【最近对话】"), "Should still include recent conversation");
    }

    @Test
    void buildContextEmptyRagChunksAndEmptySummary() {
        String ctx = contextManager.buildContext(null, List.of(), "");
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
    }

    @Test
    void buildContextTokenTruncationWorks() {
        // 构建超过 MAX_TOKENS (50000) 的文本
        StringBuilder hugeChunk = new StringBuilder();
        for (int i = 0; i < 100000; i++) hugeChunk.append('a');

        String ctx = contextManager.buildContext(null, List.of(hugeChunk.toString()), null);
        assertTrue(ctx.length() <= 50000 + 50, // +some tolerance for the suffix
            "Context should be truncated to ~MAX_TOKENS, was: " + ctx.length());
        assertTrue(ctx.contains("(上下文过长已截断)"),
            "Should include truncation marker");
    }

    @Test
    void buildContextShortTextNotTruncated() {
        String shortText = "很短的内容";
        String ctx = contextManager.buildContext(null, List.of(shortText), null);
        assertFalse(ctx.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContextNullHistory() {
        // null 历史不应抛异常
        assertDoesNotThrow(() ->
            contextManager.buildContext(null, List.of("知识"), null));
    }

    // ==================== 辅助方法 ====================

    private List<ChatMessage> generateMessages(int count) {
        List<ChatMessage> msgs = new ArrayList<>();
        // 模拟 DESC 顺序：索引越小越新
        for (int i = 0; i < count; i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            msgs.add(msg(role, "msg-" + i));
        }
        return msgs;
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
