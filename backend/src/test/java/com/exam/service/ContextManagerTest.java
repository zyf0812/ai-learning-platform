package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    @Test
    void buildContextWithAllComponents() {
        List<ChatMessage> history = createMessages(5);
        List<String> ragChunks = List.of("知识片段1", "知识片段2");
        String summary = "历史对话摘要";

        String result = manager.buildContext(history, ragChunks, summary);

        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("知识片段1"));
        assertTrue(result.contains("知识片段2"));
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("历史对话摘要"));
        assertTrue(result.contains("【最近对话】"));
        assertTrue(result.contains("user"));
        assertTrue(result.contains("assistant"));
    }

    @Test
    void buildContextWithEmptyRagChunks() {
        List<ChatMessage> history = createMessages(3);

        String result = manager.buildContext(history, List.of(), "");

        assertFalse(result.contains("【相关知识】"));
        assertTrue(result.contains("【最近对话】"));
    }

    @Test
    void buildContextWithEmptyHistory() {
        List<String> ragChunks = List.of("知识片段");

        String result = manager.buildContext(List.of(), ragChunks, "");

        assertTrue(result.contains("【相关知识】"));
        assertFalse(result.contains("【最近对话】"));
    }

    @Test
    void needsSummaryReturnsFalseForSmallHistory() {
        List<ChatMessage> history = createMessages(10);
        assertFalse(manager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsTrueForLargeHistory() {
        List<ChatMessage> history = createMessages(20);
        assertTrue(manager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsFalseForNull() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void getOldMessagesReturnsEmptyForSmallHistory() {
        List<ChatMessage> history = createMessages(5);
        assertTrue(manager.getOldMessages(history).isEmpty());
    }

    @Test
    void getOldMessagesReturnsOldMessages() {
        List<ChatMessage> history = createMessages(20);
        List<ChatMessage> old = manager.getOldMessages(history);
        assertEquals(10, old.size());
    }

    @Test
    void getOldMessagesReturnsEmptyForNull() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    @Test
    void buildContextTrimsToMaxTokens() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            longContent.append("x");
        }
        List<ChatMessage> history = List.of();
        List<String> ragChunks = List.of(longContent.toString());

        String result = manager.buildContext(history, ragChunks, "");

        assertTrue(result.length() <= 50000 + 30);
        assertTrue(result.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContextPreservesOrder() {
        List<ChatMessage> history = createMessages(3);

        String result = manager.buildContext(history, List.of(), "");

        int user1Idx = result.indexOf("user: message1");
        int assistant1Idx = result.indexOf("assistant: reply1");
        int user2Idx = result.indexOf("user: message2");

        assertTrue(user1Idx < assistant1Idx);
        assertTrue(assistant1Idx < user2Idx);
    }

    private List<ChatMessage> createMessages(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setRole("user");
            userMsg.setContent("message" + i);
            messages.add(userMsg);

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setRole("assistant");
            assistantMsg.setContent("reply" + i);
            messages.add(assistantMsg);
        }
        return messages;
    }
}