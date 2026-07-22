package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    @Test
    void buildContextWithRagChunksAndHistory() {
        List<ChatMessage> history = List.of(msg("user", "hello"), msg("assistant", "hi"));
        List<String> ragChunks = List.of("知识点1", "知识点2");
        
        String result = manager.buildContext(history, ragChunks, null);
        
        assertTrue(result.contains("【相关知识】"));
        assertTrue(result.contains("知识点1"));
        assertTrue(result.contains("知识点2"));
        assertTrue(result.contains("【最近对话】"));
        assertTrue(result.contains("user: hello"));
        assertTrue(result.contains("assistant: hi"));
    }

    @Test
    void buildContextWithSummary() {
        List<ChatMessage> history = List.of(msg("user", "hello"));
        String summary = "之前讨论了Java基础";
        
        String result = manager.buildContext(history, null, summary);
        
        assertTrue(result.contains("【对话背景】"));
        assertTrue(result.contains("之前讨论了Java基础"));
    }

    @Test
    void buildContextEmpty() {
        String result = manager.buildContext(null, null, null);
        assertEquals("", result);
    }

    @Test
    void needsSummaryWithFewMessagesReturnsFalse() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(msg("user", "msg" + i));
        }
        assertFalse(manager.needsSummary(history));
    }

    @Test
    void needsSummaryWithManyMessagesReturnsTrue() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(msg("user", "msg" + i));
        }
        assertTrue(manager.needsSummary(history));
    }

    @Test
    void needsSummaryNullReturnsFalse() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void getOldMessagesReturnsMessagesBeyondRecent() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(msg("user", "msg" + i));
        }
        
        List<ChatMessage> old = manager.getOldMessages(history);
        
        assertEquals(10, old.size());
        assertEquals("msg10", old.get(0).getContent());
    }

    @Test
    void getOldMessagesWithFewMessagesReturnsEmpty() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(msg("user", "msg" + i));
        }
        
        List<ChatMessage> old = manager.getOldMessages(history);
        
        assertTrue(old.isEmpty());
    }

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    @Test
    void buildContextTrimsToMaxTokens() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            longContent.append("x");
        }
        List<ChatMessage> history = List.of(msg("user", longContent.toString()));
        
        String result = manager.buildContext(history, null, null);
        
        assertTrue(result.contains("(上下文过长已截断)"));
        assertTrue(result.length() <= 50027);
    }

    @Test
    void buildContextOrdersMessagesAscending() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("user", "first"));
        history.add(msg("assistant", "second"));
        history.add(msg("user", "third"));
        
        String result = manager.buildContext(history, null, null);
        
        int firstIdx = result.indexOf("first");
        int secondIdx = result.indexOf("second");
        int thirdIdx = result.indexOf("third");
        
        assertTrue(firstIdx < secondIdx && secondIdx < thirdIdx);
    }

    @Test
    void buildContextLimitsRecentMessages() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(msg("user", "msg" + i));
        }
        
        String result = manager.buildContext(history, null, null);
        
        int count = 0;
        int idx = 0;
        while ((idx = result.indexOf("user: msg", idx)) != -1) {
            count++;
            idx += 10;
        }
        
        assertEquals(10, count);
        assertTrue(result.contains("msg19"));
        assertTrue(result.contains("msg10"));
        assertFalse(result.contains("msg9"));
    }
}