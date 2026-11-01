package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private List<ChatMessage> history(int size) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(message(i % 2 == 0 ? "user" : "assistant", "msg-" + i));
        }
        return list;
    }

    @Test
    void needsSummaryOnlyAboveThreshold() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(history(15)));
        assertTrue(contextManager.needsSummary(history(16)));
    }

    @Test
    void getOldMessagesReturnsOnlyBeyondRecentWindow() {
        assertTrue(contextManager.getOldMessages(null).isEmpty());
        assertTrue(contextManager.getOldMessages(history(10)).isEmpty());

        List<ChatMessage> old = contextManager.getOldMessages(history(16));
        assertEquals(6, old.size());
        assertEquals("msg-10", old.get(0).getContent());
        assertEquals("msg-15", old.get(5).getContent());
    }

    @Test
    void buildContextIncludesRagSummaryAndLatestMessages() {
        List<ChatMessage> hist = List.of(
                message("user", "newest"),
                message("assistant", "middle"),
                message("user", "oldest"));

        String ctx = contextManager.buildContext(hist, List.of("RAG-CHUNK"), "摘要内容");

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· RAG-CHUNK"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("摘要内容"));
        assertTrue(ctx.contains("【最近对话】"));
        // 历史按 DESC 传入，输出时应反转成 ASC
        assertTrue(ctx.indexOf("oldest") < ctx.indexOf("newest"));
    }

    @Test
    void buildContextLimitsHistoryToTenMostRecentMessages() {
        List<ChatMessage> hist = history(15);

        String ctx = contextManager.buildContext(hist, null, null);

        assertTrue(ctx.contains("msg-0"));
        assertTrue(ctx.contains("msg-9"));
        assertFalse(ctx.contains("msg-10"));
    }

    @Test
    void buildContextHandlesNullInputs() {
        String ctx = contextManager.buildContext(null, null, null);
        assertEquals("", ctx);
    }

    @Test
    void buildContextTruncatesOversizedContext() {
        String huge = "x".repeat(60_000);
        String ctx = contextManager.buildContext(List.of(message("user", huge)), null, null);

        assertTrue(ctx.endsWith("\n...(上下文过长已截断)"));
        assertEquals(50_000 + "\n...(上下文过长已截断)".length(), ctx.length());
    }
}
