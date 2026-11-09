package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private static List<ChatMessage> history(int size) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ChatMessage m = new ChatMessage();
            m.setRole(i % 2 == 0 ? "assistant" : "user");
            m.setContent("msg" + i);
            history.add(m);
        }
        return history;
    }

    @Test
    void emptyHistoryProducesEmptyContext() {
        assertEquals("", contextManager.buildContext(null, null, null));
        assertEquals("", contextManager.buildContext(List.of(), List.of(), null));
    }

    @Test
    void includesRagChunksAndSummarySections() {
        String context = contextManager.buildContext(List.of(), List.of("片段A", "片段B"), "之前的结论");

        assertTrue(context.contains("【相关知识】"));
        assertTrue(context.contains("· 片段A"));
        assertTrue(context.contains("· 片段B"));
        assertTrue(context.contains("【对话背景】"));
        assertTrue(context.contains("之前的结论"));
    }

    @Test
    void keepsOnlyTenMostRecentMessagesInChronologicalOrder() {
        List<ChatMessage> history = history(12); // DESC：msg0 最新

        String context = contextManager.buildContext(history, null, null);

        assertTrue(context.contains("msg9"));
        assertFalse(context.contains("msg10"));
        assertFalse(context.contains("msg11"));
        assertTrue(context.indexOf("msg9") < context.indexOf("msg0"));
    }

    @Test
    void needsSummaryOnlyWhenHistoryExceedsFifteenMessages() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(history(15)));
        assertTrue(contextManager.needsSummary(history(16)));
    }

    @Test
    void oldMessagesExcludeTheRecentWindow() {
        assertTrue(contextManager.getOldMessages(history(10)).isEmpty());
        List<ChatMessage> old = contextManager.getOldMessages(history(12));
        assertEquals(2, old.size());
        assertEquals("msg10", old.get(0).getContent());
        assertEquals("msg11", old.get(1).getContent());
    }

    @Test
    void oversizedContextIsTruncatedAndMarked() {
        String huge = "a".repeat(50001);
        String suffix = "\n...(上下文过长已截断)";

        String context = contextManager.buildContext(List.of(), List.of(huge), null);

        assertTrue(context.endsWith(suffix));
        assertEquals(50000 + suffix.length(), context.length());
    }
}
