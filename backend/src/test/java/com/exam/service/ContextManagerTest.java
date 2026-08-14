package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextManager();
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId("m-" + System.nanoTime());
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // --- buildContext() ---

    @Test
    void buildContextWithAllComponentsIncludesAllSections() {
        List<ChatMessage> history = List.of(
                msg("assistant", "好的，我来解答"),
                msg("user", "什么是Java？")
        );
        List<String> rag = List.of("Java是一种面向对象编程语言", "JVM是Java虚拟机");
        String summary = "用户此前询问过Python基础";

        String ctx = manager.buildContext(history, rag, summary);

        assertTrue(ctx.contains("【相关知识】"), "Should include RAG section");
        assertTrue(ctx.contains("Java是一种面向对象编程语言"), "Should include RAG chunks");
        assertTrue(ctx.contains("【对话背景】"), "Should include summary section");
        assertTrue(ctx.contains("用户此前询问过Python基础"), "Should include summary text");
        assertTrue(ctx.contains("【最近对话】"), "Should include recent messages section");
        // History is DESC but should be reversed to ASC in output
        int userIdx = ctx.indexOf("user: 什么是Java？");
        int assistantIdx = ctx.indexOf("assistant: 好的，我来解答");
        assertTrue(userIdx >= 0, "Should include user message");
        assertTrue(assistantIdx >= 0, "Should include assistant message");
        assertTrue(userIdx < assistantIdx, "user message should come BEFORE assistant in ASC order");
    }

    @Test
    void buildContextNullAndEmptyInputsProduceValidOutput() {
        // All nulls
        String ctx1 = manager.buildContext(null, null, null);
        assertNotNull(ctx1);
        assertFalse(ctx1.contains("【相关知识】"));
        assertFalse(ctx1.contains("【对话背景】"));
        assertFalse(ctx1.contains("【最近对话】"));

        // All empty
        String ctx2 = manager.buildContext(new ArrayList<>(), new ArrayList<>(), "");
        assertNotNull(ctx2);
        assertFalse(ctx2.contains("【相关知识】"));
        assertFalse(ctx2.contains("【对话背景】"));
        assertFalse(ctx2.contains("【最近对话】"));
    }

    @Test
    void buildContextOnlyRagChunks() {
        List<String> rag = List.of("片段1内容", "片段2内容");

        String ctx = manager.buildContext(null, rag, null);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· 片段1内容"));
        assertTrue(ctx.contains("· 片段2内容"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextOnlySummary() {
        String ctx = manager.buildContext(null, null, "对话摘要内容");

        assertFalse(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("对话摘要内容"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextLargeHistoryTruncatesToRecent() {
        // 30 messages (DESC order: most-recent first in list)
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            // i=1 is most recent, i=30 is oldest
            history.add(msg("user", "消息内容-" + i));
        }

        String ctx = manager.buildContext(history, null, null);

        // RECENT_COUNT = 10, should contain messages 1-10 (most recent 10)
        assertTrue(ctx.contains("消息内容-1"), "Should include most recent msg");
        assertTrue(ctx.contains("消息内容-10"), "Should include 10th most recent");
        // Should NOT contain older messages (11-30)
        assertFalse(ctx.contains("消息内容-11"), "Should exclude 11th and older");
        assertFalse(ctx.contains("消息内容-30"), "Should exclude oldest");
    }

    @Test
    void buildContextExactlyAtRecentCountNoTruncation() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(msg("user", "消息" + i));
        }

        String ctx = manager.buildContext(history, null, null);

        assertTrue(ctx.contains("消息1"));
        assertTrue(ctx.contains("消息10"));
    }

    @Test
    void buildContextReversesToChronologicalOrder() {
        // Input is in DESC order (recent first): user hi → user bye in history means
        // history[0] = most recent ("bye"), history[1] = older ("hi")
        List<ChatMessage> history = List.of(
                msg("assistant", "再见"),  // more recent
                msg("user", "bye"),
                msg("assistant", "你好"),   // older
                msg("user", "hi")
        );

        String ctx = manager.buildContext(history, null, null);

        int hiIdx = ctx.indexOf("user: hi");
        int niHaoIdx = ctx.indexOf("assistant: 你好");
        int byeIdx = ctx.indexOf("user: bye");
        int zaiJianIdx = ctx.indexOf("assistant: 再见");

        // Chronological (ASC) order: hi → 你好 → bye → 再见
        assertTrue(hiIdx < niHaoIdx, "hi should come before 你好");
        assertTrue(niHaoIdx < byeIdx, "你好 should come before bye");
        assertTrue(byeIdx < zaiJianIdx, "bye should come before 再见");
    }

    @Test
    void buildContextOverMaxTokensGetsTruncated() {
        // Build a string of ~100,000 chars, way over MAX_TOKENS=50000
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("中文内容填充测试数据ABCDEFG");  // ~20 chars each = 200K total
        }
        List<String> rag = List.of(sb.toString());

        String ctx = manager.buildContext(null, rag, null);

        assertTrue(ctx.length() <= 50000 + 100, "Should be trimmed near maxTokens");
        assertTrue(ctx.endsWith("...(上下文过长已截断)"), "Should end with truncation marker");
    }

    @Test
    void buildContextUnderMaxTokensNoTruncationMarker() {
        String shortContent = "简短内容";
        List<String> rag = List.of(shortContent);

        String ctx = manager.buildContext(null, rag, null);

        assertFalse(ctx.contains("...(上下文过长已截断)"));
    }

    // --- needsSummary() ---

    @Test
    void needsSummarySmallHistoryNo() {
        assertFalse(manager.needsSummary(null));
        assertFalse(manager.needsSummary(new ArrayList<>()));
        assertFalse(manager.needsSummary(List.of(
                msg("user", "a"), msg("assistant", "b")
        )));
    }

    @Test
    void needsSummaryAtThreshold() {
        // threshold = RECENT_COUNT (10) + 5 = 15
        List<ChatMessage> exactly15 = new ArrayList<>();
        for (int i = 0; i < 15; i++) exactly15.add(msg("user", "m" + i));

        // 15 is NOT > 15
        assertFalse(manager.needsSummary(exactly15));

        // 16 IS > 15
        exactly15.add(msg("user", "m16"));
        assertTrue(manager.needsSummary(exactly15));
    }

    @Test
    void needsSummaryLargeHistoryYes() {
        List<ChatMessage> large = new ArrayList<>();
        for (int i = 0; i < 50; i++) large.add(msg("user", "m" + i));
        assertTrue(manager.needsSummary(large));
    }

    // --- getOldMessages() ---

    @Test
    void getOldMessagesSmallHistoryReturnsEmpty() {
        assertEquals(List.of(), manager.getOldMessages(null));
        assertEquals(List.of(), manager.getOldMessages(new ArrayList<>()));

        List<ChatMessage> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(msg("user", "m" + i));
        assertEquals(List.of(), manager.getOldMessages(five));

        // Exactly RECENT_COUNT (10) → still empty
        List<ChatMessage> exactly10 = new ArrayList<>();
        for (int i = 0; i < 10; i++) exactly10.add(msg("user", "m" + i));
        assertEquals(List.of(), manager.getOldMessages(exactly10));
    }

    @Test
    void getOldMessagesLargeHistoryReturnsTail() {
        // 15 messages in DESC order: index 0 = most recent
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            history.add(msg("user", "msg-" + i));  // msg-1 = newest, msg-15 = oldest
        }

        List<ChatMessage> old = manager.getOldMessages(history);

        // Should return from index 10 (RECENT_COUNT) to end = msg-11 to msg-15 (5 oldest)
        assertEquals(5, old.size());
        assertEquals("msg-11", old.get(0).getContent());
        assertEquals("msg-15", old.get(4).getContent());
    }
}
