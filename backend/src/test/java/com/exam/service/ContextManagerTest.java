package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    @Test
    void buildContextKeepsOnlyTenMostRecentMessagesInChronologicalOrder() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(message(i % 2 == 0 ? "user" : "assistant", "消息" + i));
        }

        String context = contextManager.buildContext(history, List.of(), null);

        assertTrue(context.contains("【最近对话】"));
        assertFalse(context.contains("消息10"), "messages outside the recent window must be dropped");
        assertFalse(context.contains("消息11"));
        int previous = -1;
        for (int i = 9; i >= 0; i--) {
            int position = context.indexOf("消息" + i);
            assertTrue(position > previous, "messages must be rendered in chronological order");
            previous = position;
        }
    }

    @Test
    void buildContextOrdersKnowledgeThenSummaryThenRecentMessages() {
        List<ChatMessage> history = List.of(message("user", "你好"));

        String context = contextManager.buildContext(history, List.of("片段A"), "之前聊过封装");

        int knowledge = context.indexOf("【相关知识】");
        int summary = context.indexOf("【对话背景】");
        int recent = context.indexOf("【最近对话】");
        assertTrue(knowledge >= 0 && knowledge < summary && summary < recent);
        assertTrue(context.contains("片段A"));
        assertTrue(context.contains("之前聊过封装"));
        assertTrue(context.contains("user: 你好"));
    }

    @Test
    void buildContextSkipsEmptySections() {
        String context = contextManager.buildContext(List.of(), List.of(), null);

        assertEquals("", context);
    }

    @Test
    void needsSummaryOnlyAboveFifteenMessages() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(messages(15)));
        assertTrue(contextManager.needsSummary(messages(16)));
    }

    @Test
    void getOldMessagesReturnsEverythingOlderThanRecentWindow() {
        List<ChatMessage> history = messages(12);

        List<ChatMessage> old = contextManager.getOldMessages(history);

        assertEquals(2, old.size());
        assertEquals("消息10", old.get(0).getContent());
        assertEquals("消息11", old.get(1).getContent());
        assertEquals(List.of(), contextManager.getOldMessages(null));
        assertEquals(List.of(), contextManager.getOldMessages(messages(10)));
    }

    @Test
    void buildContextTruncatesContentAboveTokenBudget() {
        String huge = "字".repeat(60000);

        String context = contextManager.buildContext(List.of(message("user", huge)), List.of(), null);

        assertTrue(context.endsWith("...(上下文过长已截断)"));
        assertEquals(50000 + "\n...(上下文过长已截断)".length(), context.length());
    }

    private static List<ChatMessage> messages(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(message("user", "消息" + i));
        }
        return messages;
    }

    private static ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
