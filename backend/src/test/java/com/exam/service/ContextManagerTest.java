package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    private ChatMessage createMessage(String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setId("msg-" + role + "-" + content.hashCode());
        msg.setConversationId("conv-1");
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreatedAt(LocalDateTime.now());
        return msg;
    }

    private List<ChatMessage> createHistory(int count) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(createMessage(i % 2 == 0 ? "user" : "assistant",
                "Message " + (i + 1) + " content"));
        }
        return history;
    }

    @Nested
    @DisplayName("buildContext tests")
    class BuildContextTests {

        @Test
        @DisplayName("empty inputs produce empty context")
        void emptyInputs() {
            String ctx = contextManager.buildContext(List.of(), List.of(), null);
            assertNotNull(ctx);
            assertTrue(ctx.isEmpty());
        }

        @Test
        @DisplayName("only RAG chunks provided")
        void onlyRagChunks() {
            List<String> ragChunks = List.of("chunk1", "chunk2", "chunk3");
            String ctx = contextManager.buildContext(List.of(), ragChunks, null);
            assertTrue(ctx.contains("【相关知识】"));
            assertTrue(ctx.contains("· chunk1"));
            assertTrue(ctx.contains("· chunk2"));
            assertTrue(ctx.contains("· chunk3"));
        }

        @Test
        @DisplayName("only summary provided")
        void onlySummary() {
            String ctx = contextManager.buildContext(List.of(), List.of(), "这是一段历史摘要");
            assertTrue(ctx.contains("【对话背景】"));
            assertTrue(ctx.contains("这是一段历史摘要"));
        }

        @Test
        @DisplayName("only history provided")
        void onlyHistory() {
            List<ChatMessage> history = List.of(
                createMessage("user", "你好"),
                createMessage("assistant", "你好，有什么可以帮你的？")
            );
            String ctx = contextManager.buildContext(history, List.of(), null);
            assertTrue(ctx.contains("【最近对话】"));
            assertTrue(ctx.contains("user: 你好"));
            assertTrue(ctx.contains("assistant: 你好，有什么可以帮你的？"));
        }

        @Test
        @DisplayName("full context with RAG, summary, and history")
        void fullContext() {
            List<String> ragChunks = List.of("RAG知识1");
            List<ChatMessage> history = List.of(
                createMessage("user", "问题"),
                createMessage("assistant", "回答")
            );
            String ctx = contextManager.buildContext(history, ragChunks, "摘要");
            assertTrue(ctx.contains("【相关知识】"));
            assertTrue(ctx.contains("RAG知识1"));
            assertTrue(ctx.contains("【对话背景】"));
            assertTrue(ctx.contains("摘要"));
            assertTrue(ctx.contains("【最近对话】"));
            assertTrue(ctx.contains("user: 问题"));
        }

        @Test
        @DisplayName("history order is reversed from DESC to ASC")
        void historyOrderReversed() {
            List<ChatMessage> history = List.of(
                createMessage("user", "最新消息"),
                createMessage("user", "较早消息"),
                createMessage("user", "最早消息")
            );
            String ctx = contextManager.buildContext(history, List.of(), null);
            int latestIdx = ctx.indexOf("最新消息");
            int earlierIdx = ctx.indexOf("较早消息");
            int earliestIdx = ctx.indexOf("最早消息");
            assertTrue(earliestIdx < earlierIdx);
            assertTrue(earlierIdx < latestIdx);
        }

        @Test
        @DisplayName("null inputs are handled gracefully")
        void nullInputs() {
            String ctx = contextManager.buildContext(null, null, null);
            assertNotNull(ctx);
            assertTrue(ctx.isEmpty());
        }

        @Test
        @DisplayName("history with more than RECENT_COUNT only keeps recent ones")
        void historyTruncation() {
            List<ChatMessage> history = createHistory(25);
            String ctx = contextManager.buildContext(history, List.of(), null);
            assertTrue(ctx.contains("【最近对话】"));
            for (int i = 0; i < 10; i++) {
                assertTrue(ctx.contains("Message " + (i + 1)),
                    "Should contain recent message " + (i + 1));
            }
        }

        @Test
        @DisplayName("token trimming for long context")
        void tokenTrimming() {
            StringBuilder longContent = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                longContent.append("这是一段很长的文本内容用于测试截断功能");
            }
            List<String> ragChunks = List.of(longContent.toString());
            String ctx = contextManager.buildContext(List.of(), ragChunks, null);
            assertTrue(ctx.contains("...(上下文过长已截断)"));
        }
    }

    @Nested
    @DisplayName("needsSummary tests")
    class NeedsSummaryTests {

        @Test
        @DisplayName("returns false when history is below threshold")
        void belowThreshold() {
            assertFalse(contextManager.needsSummary(createHistory(5)));
            assertFalse(contextManager.needsSummary(createHistory(10)));
            assertFalse(contextManager.needsSummary(createHistory(14)));
        }

        @Test
        @DisplayName("returns true when history exceeds threshold")
        void aboveThreshold() {
            assertTrue(contextManager.needsSummary(createHistory(16)));
            assertTrue(contextManager.needsSummary(createHistory(20)));
        }

        @Test
        @DisplayName("returns false for null or empty history")
        void nullOrEmpty() {
            assertFalse(contextManager.needsSummary(null));
            assertFalse(contextManager.needsSummary(List.of()));
        }

        @Test
        @DisplayName("threshold boundary at RECENT_COUNT + 5 + 1")
        void boundaryThreshold() {
            assertFalse(contextManager.needsSummary(createHistory(15)));
            assertTrue(contextManager.needsSummary(createHistory(16)));
        }
    }

    @Nested
    @DisplayName("getOldMessages tests")
    class GetOldMessagesTests {

        @Test
        @DisplayName("returns empty list when history is below RECENT_COUNT")
        void belowRecentCount() {
            List<ChatMessage> result = contextManager.getOldMessages(createHistory(5));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns old messages excluding recent ones")
        void returnsOldMessages() {
            List<ChatMessage> history = createHistory(20);
            List<ChatMessage> old = contextManager.getOldMessages(history);
            assertEquals(10, old.size());
        }

        @Test
        @DisplayName("returns empty list for null history")
        void nullHistory() {
            List<ChatMessage> result = contextManager.getOldMessages(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns all messages when history size equals RECENT_COUNT")
        void exactRecentCount() {
            List<ChatMessage> history = createHistory(10);
            List<ChatMessage> old = contextManager.getOldMessages(history);
            assertTrue(old.isEmpty());
        }

        @Test
        @DisplayName("returns only excess messages beyond RECENT_COUNT")
        void onlyExcessMessages() {
            List<ChatMessage> history = createHistory(12);
            List<ChatMessage> old = contextManager.getOldMessages(history);
            assertEquals(2, old.size());
            assertEquals("Message 11 content", old.get(0).getContent());
            assertEquals("Message 12 content", old.get(1).getContent());
        }
    }
}