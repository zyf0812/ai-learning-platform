package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    @Test
    void needsSummaryReturnsFalseWhenBelowThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            history.add(createMessage("user", "msg" + i));
        }
        assertFalse(manager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsTrueWhenAboveThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            history.add(createMessage("user", "msg" + i));
        }
        assertTrue(manager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsFalseForEmptyHistory() {
        assertFalse(manager.needsSummary(null));
        assertFalse(manager.needsSummary(new ArrayList<>()));
    }

    @Test
    void getOldMessagesReturnsEmptyWhenBelowThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(createMessage("user", "msg" + i));
        }
        assertTrue(manager.getOldMessages(history).isEmpty());
    }

    @Test
    void getOldMessagesReturnsCorrectSubset() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(createMessage("user", "msg" + i));
        }
        List<ChatMessage> old = manager.getOldMessages(history);
        assertEquals(10, old.size());
        assertEquals("msg10", old.get(0).getContent());
        assertEquals("msg19", old.get(9).getContent());
    }

    @Test
    void buildContextWithAllParts() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(createMessage("user", "hello"));
        history.add(createMessage("assistant", "hi"));

        List<String> ragChunks = List.of("chunk1", "chunk2");
        String summary = "summary text";

        String result = manager.buildContext(history, ragChunks, summary);

        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("chunk1"));
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("summary text"));
        assertTrue(result.contains("【最近对话】"));
        assertTrue(result.contains("user: hello"));
        assertTrue(result.contains("assistant: hi"));
    }

    @Test
    void buildContextWithEmptyHistory() {
        List<String> ragChunks = List.of("chunk1");
        String result = manager.buildContext(null, ragChunks, null);
        assertTrue(result.contains("【相关知识】"));
        assertFalse(result.contains("【最近对话】"));
    }

    @Test
    void buildContextOrdersMessagesAscending() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(createMessage("assistant", "response"));
        history.add(createMessage("user", "question"));

        String result = manager.buildContext(history, null, null);

        int userIdx = result.indexOf("user: question");
        int assistantIdx = result.indexOf("assistant: response");
        assertTrue(userIdx < assistantIdx);
    }

    @Test
    void buildContextTrimsToMaxTokens() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            longText.append("x");
        }
        List<ChatMessage> history = new ArrayList<>();
        history.add(createMessage("user", longText.toString()));

        String result = manager.buildContext(history, null, null);

        assertTrue(result.length() <= 50010);
        assertTrue(result.contains("...(上下文过长已截断)"));
    }

    @Test
    void buildContextWithNullRagChunks() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(createMessage("user", "test"));

        String result = manager.buildContext(history, null, null);
        assertFalse(result.contains("【相关知识】"));
        assertTrue(result.contains("【最近对话】"));
    }

    private ChatMessage createMessage(String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setId("test-id");
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreatedAt(LocalDateTime.now());
        return msg;
    }
}