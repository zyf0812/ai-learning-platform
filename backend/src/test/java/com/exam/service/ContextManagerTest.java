package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager ctx;

    @BeforeEach
    void setUp() {
        ctx = new ContextManager();
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(java.util.UUID.randomUUID().toString().substring(0, 8));
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private List<ChatMessage> historyDesc(int n) {
        // 返回 DESC 顺序的历史：最新的在 index 0
        List<ChatMessage> list = new ArrayList<>();
        for (int i = n; i >= 1; i--) {
            String role = (i % 2 == 1) ? "user" : "assistant";
            list.add(msg(role, "消息" + i));
        }
        return list;
    }

    // ===== needsSummary =====

    @Test
    void needsSummaryNullHistoryFalse() {
        assertFalse(ctx.needsSummary(null));
    }

    @Test
    void needsSummarySmallHistoryFalse() {
        // RECENT_COUNT=10, 阈值 RECENT_COUNT+5=15
        assertFalse(ctx.needsSummary(historyDesc(10)));
        assertFalse(ctx.needsSummary(historyDesc(14)));
        assertFalse(ctx.needsSummary(historyDesc(15))); // 刚好等于阈值也不需要
    }

    @Test
    void needsSummaryLargeHistoryTrue() {
        assertTrue(ctx.needsSummary(historyDesc(16)));
        assertTrue(ctx.needsSummary(historyDesc(100)));
    }

    // ===== getOldMessages =====

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(ctx.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessagesExactlyRecentCountReturnsEmpty() {
        // RECENT_COUNT=10，刚好 10 条不返回旧消息
        List<ChatMessage> h = historyDesc(10);
        List<ChatMessage> old = ctx.getOldMessages(h);
        assertTrue(old.isEmpty());
    }

    @Test
    void getOldMessagesFewerThanRecentReturnsEmpty() {
        assertTrue(ctx.getOldMessages(historyDesc(5)).isEmpty());
    }

    @Test
    void getOldMessagesMoreThanRecentReturnsTail() {
        // 12 条 (DESC): idx 0~9 = 最近 10 条 (msg12~msg3)，idx 10~11 = 旧消息 (msg2, msg1)
        List<ChatMessage> h = historyDesc(12);
        List<ChatMessage> old = ctx.getOldMessages(h);
        assertEquals(2, old.size());
        // 旧消息是 history 的 subList(10, 12)，即 DESC 中最早的消息
        assertEquals("消息2", old.get(0).getContent());
        assertEquals("消息1", old.get(1).getContent());
    }

    // ===== buildContext =====

    @Test
    void buildContextEmptyInputsNotEmptyThrows() {
        String result = ctx.buildContext(null, null, null);
        assertNotNull(result);
        assertTrue(result.isEmpty() || result.trim().isEmpty());
    }

    @Test
    void buildContextWithRagChunksOnly() {
        List<String> chunks = List.of("知识点1：Spring IoC", "知识点2：依赖注入");
        String result = ctx.buildContext(null, chunks, null);

        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("知识点1：Spring IoC"));
        assertTrue(result.contains("知识点2：依赖注入"));
    }

    @Test
    void buildContextWithSummaryOnly() {
        String result = ctx.buildContext(null, List.of(), "之前讨论了AOP原理");
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("之前讨论了AOP原理"));
    }

    @Test
    void buildContextWithRecentHistoryReversesToAsc() {
        // 输入 DESC: [msg5(u), msg4(a), msg3(u), msg2(a), msg1(u)]
        // 最近对话应按 ASC 展示: msg1 → msg2 → ... → msg5
        List<ChatMessage> h = historyDesc(5);
        String result = ctx.buildContext(h, List.of(), null);

        assertTrue(result.contains("【最近对话】"));
        // 检查 ASC 顺序（user 的 msg1 在前面, msg5 在后面）
        int idx1 = result.indexOf("消息1");
        int idx5 = result.indexOf("消息5");
        assertTrue(idx1 < idx5, "消息1 应出现在消息5之前 (ASC)");
    }

    @Test
    void buildContextLargeHistoryTruncatesToRecent() {
        // 50 条消息 → 只保留最近 10 条原文
        List<ChatMessage> h = historyDesc(50);
        String result = ctx.buildContext(h, List.of(), null);

        // 应包含最近 10 条 (msg50 ~ msg41)，不含 msg40 及更早
        assertTrue(result.contains("消息50"));
        assertTrue(result.contains("消息41"));
        assertFalse(result.contains("消息40"));
        assertFalse(result.contains("消息1"));
    }

    @Test
    void buildContextAllSectionsCombined() {
        List<ChatMessage> h = historyDesc(3);
        List<String> chunks = List.of("RAG片段内容");
        String summary = "对话摘要";

        String result = ctx.buildContext(h, chunks, summary);

        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("【最近对话】"));
        assertTrue(result.contains("RAG片段内容"));
        assertTrue(result.contains("对话摘要"));
        assertTrue(result.contains("user: 消息1"));
    }

    @Test
    void buildContextTrimsVeryLongContext() {
        // MAX_TOKENS = 50000，用非常长的字符串触发截断
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60000; i++) sb.append('x');
        List<String> bigChunk = List.of(sb.toString());

        String result = ctx.buildContext(null, bigChunk, null);
        assertTrue(result.length() <= 50000 + 100, // +100 给截断后缀
            "上下文应被截断，实际长度: " + result.length());
        assertTrue(result.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContextShortContextNoTruncationSuffix() {
        String shortContent = "短内容";
        String result = ctx.buildContext(null, List.of(shortContent), null);
        assertFalse(result.contains("(上下文过长已截断)"),
            "短内容不应有截断标记");
    }
}
