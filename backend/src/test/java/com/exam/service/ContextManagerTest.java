package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    @Test
    void buildContextIncludesRagSummaryAndChronologicalRecentMessages() {
        List<ChatMessage> history = List.of(
            message("m3", "user", "第三个问题"),
            message("m2", "assistant", "第二个回答"),
            message("m1", "user", "第一个问题"));

        String context = contextManager.buildContext(history, List.of("片段A", "片段B"), "历史摘要");

        int ragIdx = context.indexOf("【相关知识】");
        int summaryIdx = context.indexOf("【对话背景】");
        int recentIdx = context.indexOf("【最近对话】");
        assertTrue(ragIdx >= 0 && summaryIdx > ragIdx && recentIdx > summaryIdx);

        assertTrue(context.contains("· 片段A"));
        assertTrue(context.contains("· 片段B"));
        assertTrue(context.contains("历史摘要"));

        // 输入是 DESC，输出应按时间正序排列
        int first = context.indexOf("第一个问题");
        int second = context.indexOf("第二个回答");
        int third = context.indexOf("第三个问题");
        assertTrue(first >= 0 && second > first && third > second);
    }

    @Test
    void buildContextKeepsOnlyTenMostRecentMessages() {
        List<ChatMessage> history = new ArrayList<>();
        // DESC：index 0 最新
        for (int i = 12; i >= 1; i--) {
            history.add(message("m" + i, "user", String.format("content-%02d", i)));
        }

        String context = contextManager.buildContext(history, List.of(), null);

        assertTrue(context.contains("content-12"));
        assertTrue(context.contains("content-03"));
        assertFalse(context.contains("content-02"));
        assertFalse(context.contains("content-01"));
    }

    @Test
    void needsSummaryBoundary() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(messages(15)));
        assertTrue(contextManager.needsSummary(messages(16)));
    }

    @Test
    void getOldMessagesReturnsOnlyOverflowBeyondRecentWindow() {
        List<ChatMessage> twelve = messages(12);

        List<ChatMessage> old = contextManager.getOldMessages(twelve);

        assertEquals(2, old.size());
        assertEquals("content-02", old.get(0).getContent());
        assertEquals("content-01", old.get(1).getContent());
        assertTrue(contextManager.getOldMessages(messages(10)).isEmpty());
        assertTrue(contextManager.getOldMessages(null).isEmpty());
    }

    @Test
    void buildContextTruncatesWhenExceedingTokenBudget() {
        String huge = "a".repeat(60000);

        String context = contextManager.buildContext(List.of(message("m1", "user", huge)), List.of(), null);

        assertTrue(context.endsWith("...(上下文过长已截断)"));
        assertTrue(context.length() < 60000);
    }

    private static List<ChatMessage> messages(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            list.add(message("m" + i, "user", String.format("content-%02d", i)));
        }
        return list;
    }

    private static ChatMessage message(String id, String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
