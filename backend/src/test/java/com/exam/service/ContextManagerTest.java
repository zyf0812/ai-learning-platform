package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager cm;

    @BeforeEach
    void setUp() {
        cm = new ContextManager();
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    // ==================== buildContext: 各段组装 ====================

    @Test
    void buildContextWithOnlyRagChunks() {
        List<String> rag = List.of("Java是一门面向对象语言", "Spring提供IoC容器");
        String ctx = cm.buildContext(List.of(), rag, null);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· Java是一门面向对象语言"));
        assertTrue(ctx.contains("· Spring提供IoC容器"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextWithOnlySummary() {
        String ctx = cm.buildContext(List.of(), List.of(), "用户之前询问了JVM内存模型");

        assertFalse(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("JVM内存模型"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextWithRecentHistoryLessThanThreshold() {
        // history 为 DESC，前 N 条是最近的；< RECENT_COUNT(10) 全部保留
        List<ChatMessage> hist = List.of(
            msg("assistant", "这是最近的回复"),  // index 0 = 最新
            msg("user", "最后一个问题")          // index 1
        );
        String ctx = cm.buildContext(hist, List.of(), null);

        assertTrue(ctx.contains("【最近对话】"));
        // ordered 反转后 ASC: user → assistant
        int userIdx = ctx.indexOf("user: 最后一个问题");
        int asstIdx = ctx.indexOf("assistant: 这是最近的回复");
        assertTrue(userIdx >= 0 && asstIdx >= 0);
        assertTrue(userIdx < asstIdx, "历史顺序应为 ASC：旧消息在前");
    }

    @Test
    void buildContextHistoryExceedsRecentKeepsOnlyTopN() {
        // 构造 15 条消息（DESC），只保留前 10 条
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            hist.add(msg("user", "msg-" + i));  // msg-0 = 最新，msg-14 = 最旧
        }
        String ctx = cm.buildContext(hist, List.of(), null);

        // 保留 msg-0 到 msg-9（最新10条）
        for (int i = 0; i < 10; i++) {
            assertTrue(ctx.contains("msg-" + i), "应包含最近消息 msg-" + i);
        }
        // 丢弃 msg-10 到 msg-14
        for (int i = 10; i < 15; i++) {
            assertFalse(ctx.contains("msg-" + i), "不应包含旧消息 msg-" + i);
        }
    }

    @Test
    void buildContextAllThreeSectionsPresent() {
        List<String> rag = List.of("RAG知识1");
        String summary = "之前聊了A和B";
        List<ChatMessage> hist = List.of(
            msg("assistant", "答案C"),
            msg("user", "问题C")
        );

        String ctx = cm.buildContext(hist, rag, summary);

        assertTrue(ctx.contains("【相关知识】"), "缺少RAG段落");
        assertTrue(ctx.contains("【对话背景】"), "缺少摘要段落");
        assertTrue(ctx.contains("【最近对话】"), "缺少最近对话段落");
    }

    @Test
    void buildContextNullInputsHandleGracefully() {
        assertDoesNotThrow(() -> cm.buildContext(null, null, null));
    }

    // ==================== needsSummary 阈值 ====================

    @Test
    void needsSummaryBelowThresholdFalse() {
        // RECENT_COUNT=10, threshold = 10+5 = 15
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 15; i++) hist.add(msg("user", "m" + i));
        assertFalse(cm.needsSummary(hist), "15条刚好等于阈值不应摘要");
    }

    @Test
    void needsSummaryAboveThresholdTrue() {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 16; i++) hist.add(msg("user", "m" + i));
        assertTrue(cm.needsSummary(hist), "16条>阈值应摘要");
    }

    @Test
    void needsSummaryNullHistoryFalse() {
        assertFalse(cm.needsSummary(null));
    }

    // ==================== getOldMessages ====================

    @Test
    void getOldMessagesWithinLimitReturnsEmpty() {
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 10; i++) hist.add(msg("u", "m" + i));
        assertTrue(cm.getOldMessages(hist).isEmpty());
    }

    @Test
    void getOldMessagesExceedsLimitReturnsTail() {
        // DESC 排序：index 0-9 最近，index 10+ 旧消息
        List<ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 14; i++) hist.add(msg("u", "m" + i));
        List<ChatMessage> old = cm.getOldMessages(hist);
        assertEquals(4, old.size());
        // 子列表应该从 RECENT_COUNT(10) 开始
        assertEquals("m10", old.get(0).getContent());
        assertEquals("m13", old.get(3).getContent());
    }

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(cm.getOldMessages(null).isEmpty());
    }

    // ==================== token 截断 ====================

    @Test
    void buildContextVeryLongTextGetsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60000; i++) sb.append('a');
        String ctx = cm.buildContext(List.of(), List.of(sb.toString()), null);
        // MAX_TOKENS = 50000，超过会截断并加标记
        assertTrue(ctx.endsWith("\n...(上下文过长已截断)") || ctx.length() <= 50100);
    }
}
