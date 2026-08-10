package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        contextManager = new ContextManager();
    }

    // === needsSummary() tests ===

    @Test
    void needsSummaryReturnsFalseForSmallHistory() {
        List<ChatMessage> history = createMessages(5);
        assertFalse(contextManager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsFalseForExactThreshold() {
        List<ChatMessage> history = createMessages(15);
        assertFalse(contextManager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsTrueForLargeHistory() {
        List<ChatMessage> history = createMessages(16);
        assertTrue(contextManager.needsSummary(history));
    }

    @Test
    void needsSummaryReturnsTrueForNullHistory() {
        assertFalse(contextManager.needsSummary(null));
    }

    @Test
    void needsSummaryReturnsFalseForEmptyHistory() {
        assertFalse(contextManager.needsSummary(List.of()));
    }

    // === getOldMessages() tests ===

    @Test
    void getOldMessagesReturnsEmptyWhenHistorySmall() {
        List<ChatMessage> history = createMessages(5);
        List<ChatMessage> old = contextManager.getOldMessages(history);
        assertTrue(old.isEmpty());
    }

    @Test
    void getOldMessagesReturnsEmptyForNull() {
        List<ChatMessage> old = contextManager.getOldMessages(null);
        assertTrue(old.isEmpty());
    }

    @Test
    void getOldMessagesReturnsOldMessagesBeyondRecent() {
        List<ChatMessage> history = createMessages(15);
        List<ChatMessage> old = contextManager.getOldMessages(history);
        assertEquals(5, old.size());
    }

    // === buildContext() tests ===

    @Test
    void buildContextWithRagChunksAndHistoryAndSummary() {
        List<String> ragChunks = List.of("Chunk 1 content", "Chunk 2 content");
        List<ChatMessage> history = createMessages(3);
        String summary = "Previous conversation summary";

        String context = contextManager.buildContext(history, ragChunks, summary);

        assertNotNull(context);
        assertTrue(context.contains("【相关知识】"));
        assertTrue(context.contains("Chunk 1 content"));
        assertTrue(context.contains("Chunk 2 content"));
        assertTrue(context.contains("【对话背景】"));
        assertTrue(context.contains("Previous conversation summary"));
        assertTrue(context.contains("【最近对话】"));
    }

    @Test
    void buildContextWithOnlyHistory() {
        List<ChatMessage> history = createMessages(2);

        String context = contextManager.buildContext(history, null, null);

        assertNotNull(context);
        assertTrue(context.contains("【最近对话】"));
        assertFalse(context.contains("【相关知识】"));
        assertFalse(context.contains("【对话背景】"));
    }

    @Test
    void buildContextWithOnlyRagChunks() {
        List<String> ragChunks = List.of("Relevant chunk");

        String context = contextManager.buildContext(null, ragChunks, null);

        assertTrue(context.contains("【相关知识】"));
        assertTrue(context.contains("Relevant chunk"));
        assertFalse(context.contains("【最近对话】"));
    }

    @Test
    void buildContextWithOnlySummary() {
        String summary = "Conversation summary text";

        String context = contextManager.buildContext(null, null, summary);

        assertTrue(context.contains("【对话背景】"));
        assertTrue(context.contains("Conversation summary text"));
    }

    @Test
    void buildContextWithEmptyInputsReturnsEmpty() {
        String context = contextManager.buildContext(List.of(), List.of(), null);
        assertNotNull(context);
    }

    @Test
    void buildContextHistoryIsReversedToAscOrder() {
        ChatMessage userMsg = createMessage("u1", "user", "User question");
        ChatMessage aiMsg = createMessage("u2", "assistant", "AI answer");
        List<ChatMessage> history = List.of(userMsg, aiMsg);

        String context = contextManager.buildContext(history, null, null);

        int userIdx = context.indexOf("user: User question");
        int aiIdx = context.indexOf("assistant: AI answer");
        assertTrue(userIdx < aiIdx);
    }

    @Test
    void buildContextTrimsToMaxTokens() {
        StringBuilder largeChunk = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            largeChunk.append("x");
        }
        List<String> ragChunks = List.of(largeChunk.toString());

        String context = contextManager.buildContext(List.of(), ragChunks, null);
        assertTrue(context.length() <= 50000 + 50);
        assertTrue(context.contains("上下文过长已截断"));
    }

    @Test
    void buildContextDoesNotTrimShortContent() {
        List<String> ragChunks = List.of("Short content");

        String context = contextManager.buildContext(List.of(), ragChunks, null);
        assertFalse(context.contains("上下文过长已截断"));
    }

    // === helper methods ===

    private List<ChatMessage> createMessages(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(createMessage("msg" + i, i % 2 == 0 ? "user" : "assistant",
                    "Message " + i));
        }
        return messages;
    }

    private ChatMessage createMessage(String id, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreatedAt(LocalDateTime.now());
        return msg;
    }
}