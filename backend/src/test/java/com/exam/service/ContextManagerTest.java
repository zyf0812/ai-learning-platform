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
        m.setId("m" + System.nanoTime());
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // ==================== needsSummary ====================

    @Test
    void needsSummary_emptyOrShortReturnsFalse() {
        assertFalse(manager.needsSummary(null));
        assertFalse(manager.needsSummary(List.of()));
        // RECENT_COUNT=10, 阈值=RECENT_COUNT+5=15
        assertFalse(manager.needsSummary(List.of(
            msg("u","1"),msg("a","2"),msg("u","3"),msg("a","4"),msg("u","5"),
            msg("a","6"),msg("u","7"),msg("a","8"),msg("u","9"),msg("a","10"),
            msg("u","11"),msg("a","12"),msg("u","13"),msg("a","14"),msg("u","15")
        )));
    }

    @Test
    void needsSummary_exceeds15ReturnsTrue() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 16; i++) msgs.add(msg("user", "m" + i));
        assertTrue(manager.needsSummary(msgs));
    }

    // ==================== getOldMessages ====================

    @Test
    void getOldMessages_smallHistoryReturnsEmpty() {
        assertEquals(List.of(), manager.getOldMessages(null));
        assertEquals(List.of(), manager.getOldMessages(List.of()));
        assertEquals(List.of(), manager.getOldMessages(List.of(
            msg("u","a"), msg("u","b")
        )));
    }

    @Test
    void getOldMessages_exact10ReturnsEmpty() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) msgs.add(msg("u", "m" + i));
        assertEquals(List.of(), manager.getOldMessages(msgs));
    }

    @Test
    void getOldMessages_12ReturnsLast2() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ChatMessage m = msg("user", "msg-" + i);
            msgs.add(m);
        }
        // history 是 DESC 顺序（最新在前），subList(10, 12) = 最旧的 2 条
        var old = manager.getOldMessages(msgs);
        assertEquals(2, old.size());
        assertEquals("msg-10", old.get(0).getContent());
        assertEquals("msg-11", old.get(1).getContent());
    }

    // ==================== buildContext 结构 ====================

    @Test
    void buildContext_ragChunksAppearFirst() {
        List<ChatMessage> history = List.of(msg("assistant", "最近回答"));
        String ctx = manager.buildContext(history, List.of("知识A内容", "知识B内容"), null);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.indexOf("知识A内容") < ctx.indexOf("【最近对话】"),
            "RAG 片段应该出现在最近对话之前");
    }

    @Test
    void buildContext_summaryPlacedBetweenRagAndRecent() {
        List<ChatMessage> history = List.of(msg("user", "你好"));
        String ctx = manager.buildContext(history, List.of("RAG知识"), "这是摘要");

        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("这是摘要"));
        int ragIdx = ctx.indexOf("【相关知识】");
        int summaryIdx = ctx.indexOf("【对话背景】");
        int recentIdx = ctx.indexOf("【最近对话】");
        assertTrue(ragIdx < summaryIdx && summaryIdx < recentIdx,
            "顺序应为: RAG -> 摘要 -> 最近对话");
    }

    @Test
    void buildContext_recentMessagesReversedToChronological() {
        // history 是 DESC：最新在前 [新 -> 旧]
        List<ChatMessage> history = List.of(
            msg("user", "第三条"),     // index 0 最新
            msg("assistant", "第二条"),
            msg("user", "第一条")       // index 2 最旧
        );

        String ctx = manager.buildContext(history, List.of(), null);

        // 展示给 LLM 时应为 ASC 顺序：user:第一条 / assistant:第二条 / user:第三条
        int first = ctx.indexOf("第一条");
        int second = ctx.indexOf("第二条");
        int third = ctx.indexOf("第三条");
        assertTrue(first < second && second < third,
            "最近对话应反转成时间正序");
    }

    @Test
    void buildContext_moreThan10TakesOnlyRecent10() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            history.add(msg(i % 2 == 0 ? "user" : "assistant", "msg-" + i));
        }
        String ctx = manager.buildContext(history, List.of(), null);

        // 最新的 10 条应该在里面（msg-0 到 msg-9，history[0..9]）
        for (int i = 0; i < 10; i++) {
            assertTrue(ctx.contains("msg-" + i), "应包含最近的 msg-" + i);
        }
        // 最旧的第 25 条(msg-24, msg-23...)不应出现在"最近对话"
        assertFalse(ctx.contains("msg-24"), "不应包含最旧的 msg-24 作为最近对话条目");
    }

    @Test
    void buildContext_emptyInputsNoCrashes() {
        String ctx = manager.buildContext(List.of(), List.of(), null);
        assertNotNull(ctx);
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
    }

    @Test
    void buildContext_nullInputsHandledGracefully() {
        String ctx = manager.buildContext(null, null, null);
        assertNotNull(ctx);
        assertFalse(ctx.contains("null"));
    }

    // ==================== token 截断 ====================

    @Test
    void buildContext_veryLongContentGetsTruncated() {
        StringBuilder sb = new StringBuilder();
        // 构造一个远超 MAX_TOKENS (50000) 的长内容
        String base = "这是一段非常长的文档内容用于测试截断逻辑 ABCDEFG.";
        while (sb.length() < 60000) sb.append(base);

        String ctx = manager.buildContext(List.of(), List.of(sb.toString()), null);
        // 截断后长度应接近 MAX_TOKENS 并带提示
        assertTrue(ctx.length() <= 50100, "截断后应 <= 50000 + 提示文字");
        assertTrue(ctx.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContext_shortContentUntouched() {
        String small = "Hello, 这是测试.";
        String ctx = manager.buildContext(List.of(), List.of(small), null);
        assertFalse(ctx.contains("(上下文过长已截断)"));
        assertTrue(ctx.contains(small));
    }
}
