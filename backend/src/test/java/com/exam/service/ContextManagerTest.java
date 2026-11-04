package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private static final String TRUNCATED_MARKER = "\n...(上下文过长已截断)";

    private final ContextManager manager = new ContextManager();

    private List<ChatMessage> history(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ChatMessage m = new ChatMessage();
            m.setRole("user");
            m.setContent("m" + i);
            list.add(m);
        }
        return list;
    }

    @Test
    void needsSummaryOnlyAfterRecentWindowPlusSlack() {
        assertFalse(manager.needsSummary(null));
        assertFalse(manager.needsSummary(history(15)));
        assertTrue(manager.needsSummary(history(16)));
    }

    @Test
    void getOldMessagesExcludesRecentWindow() {
        assertTrue(manager.getOldMessages(null).isEmpty());
        assertTrue(manager.getOldMessages(history(10)).isEmpty());

        List<ChatMessage> old = manager.getOldMessages(history(12));
        assertEquals(2, old.size());
        assertEquals("m11", old.get(0).getContent());
        assertEquals("m12", old.get(1).getContent());
    }

    @Test
    void buildContextIncludesRagSummaryAndTenMostRecentMessagesInChronologicalOrder() {
        List<ChatMessage> history = history(12);
        String context = manager.buildContext(history,
            List.of("文档片段"), "历史摘要");

        assertTrue(context.contains("【相关知识】"));
        assertTrue(context.contains("· 文档片段"));
        assertTrue(context.contains("【对话背景】\n历史摘要"));
        assertTrue(context.contains("【最近对话】"));

        // 只保留最近 10 条（m1~m10），旧的 m11/m12 不应出现
        assertFalse(context.contains("user: m11"));
        assertFalse(context.contains("user: m12"));
        int newerIndex = context.indexOf("user: m1\n");
        assertTrue(newerIndex > context.indexOf("user: m10\n"), "最近消息应按时间正序排列");
    }

    @Test
    void buildContextTruncatesBeyondTokenBudget() {
        String context = manager.buildContext(List.of(), List.of("x".repeat(60_000)), null);

        assertTrue(context.endsWith(TRUNCATED_MARKER));
        assertEquals(50_000 + TRUNCATED_MARKER.length(), context.length());
    }

    @Test
    void buildContextReturnsEmptyStringForNoInput() {
        assertEquals("", manager.buildContext(List.of(), List.of(), null));
    }
}
