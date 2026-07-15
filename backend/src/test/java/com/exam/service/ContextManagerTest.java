package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    @Test
    void buildContextWithRagChunks() {
        List<ChatMessage> history = List.of();
        List<String> ragChunks = List.of("知识点1内容", "知识点2内容");
        String result = manager.buildContext(history, ragChunks, null);
        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("知识点1内容"));
        assertTrue(result.contains("知识点2内容"));
    }

    @Test
    void buildContextWithSummary() {
        List<ChatMessage> history = List.of();
        List<String> ragChunks = List.of();
        String result = manager.buildContext(history, ragChunks, "对话摘要内容");
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("对话摘要内容"));
    }

    @Test
    void buildContextWithRecentMessages() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ChatMessage msg = new ChatMessage();
            msg.setRole(i % 2 == 0 ? "user" : "assistant");
            msg.setContent("消息" + i);
            history.add(msg);
        }
        String result = manager.buildContext(history, List.of(), null);
        assertTrue(result.contains("【最近对话】"));
        assertTrue(result.contains("消息0"));
        assertTrue(result.contains("消息4"));
    }

    @Test
    void needsSummaryReturnsFalseWhenUnderThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            history.add(new ChatMessage());
        }
        assertFalse(manager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsTrueWhenOverThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            history.add(new ChatMessage());
        }
        assertTrue(manager.needsSummary(history));
    }

    @Test
    void getOldMessagesReturnsCorrectSubset() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ChatMessage msg = new ChatMessage();
            msg.setContent("消息" + i);
            history.add(msg);
        }
        List<ChatMessage> old = manager.getOldMessages(history);
        assertEquals(10, old.size());
        assertEquals("消息10", old.get(0).getContent());
        assertEquals("消息19", old.get(9).getContent());
    }

    @Test
    void getOldMessagesReturnsEmptyWhenUnderThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new ChatMessage());
        }
        assertTrue(manager.getOldMessages(history).isEmpty());
    }

    @Test
    void buildContextTrimsToMaxTokens() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            longContent.append("x");
        }
        List<ChatMessage> history = List.of();
        String result = manager.buildContext(history, List.of(longContent.toString()), null);
        assertTrue(result.length() <= 50028);
        assertTrue(result.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContextWithAllComponents() {
        List<ChatMessage> history = new ArrayList<>();
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent("用户提问");
        history.add(msg);

        List<String> ragChunks = List.of("RAG内容");
        String result = manager.buildContext(history, ragChunks, "对话摘要");

        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("【最近对话】"));
        assertTrue(result.contains("RAG内容"));
        assertTrue(result.contains("对话摘要"));
        assertTrue(result.contains("用户提问"));
    }

    @Test
    void needsSummaryWithNullHistory() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void getOldMessagesWithNullHistory() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    @Test
    void buildContextWithNullInputs() {
        String result = manager.buildContext(null, null, null);
        assertNotNull(result);
    }
}