package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 是对话上下文拼装与压缩的共享核心逻辑，
 * 此前没有任何覆盖，这里锁定：消息保留窗口、摘要触发阈值、排序与 token 截断。
 */
class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private static ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** history 按生产代码约定为 DESC（最新在前），12 条时只保留最近 10 条 */
    private static List<ChatMessage> descendingHistory(int count) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            history.add(message("user", "内容[" + i + "]"));
        }
        return history;
    }

    @Test
    void buildContextWithoutAnyDataReturnsEmptyString() {
        assertEquals("", contextManager.buildContext(null, null, null));
        assertEquals("", contextManager.buildContext(List.of(), List.of(), ""));
    }

    @Test
    void buildContextIncludesRagChunksSummaryAndRecentMessages() {
        List<ChatMessage> history = List.of(
                message("assistant", "最新回答"),
                message("user", "较早提问"),
                message("user", "最旧提问"));

        String ctx = contextManager.buildContext(history, List.of("片段A"), "历史摘要");

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· 片段A"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("历史摘要"));
        assertTrue(ctx.contains("【最近对话】"));
        // DESC 输入应按时间正序展示
        assertTrue(ctx.indexOf("最旧提问") < ctx.indexOf("最新回答"));
    }

    @Test
    void buildContextKeepsOnlyTenMostRecentMessages() {
        List<ChatMessage> history = descendingHistory(12);

        String ctx = contextManager.buildContext(history, List.of(), null);

        assertTrue(ctx.contains("内容[12]"));
        assertTrue(ctx.contains("内容[3]"));
        assertFalse(ctx.contains("内容[2]"));
        assertFalse(ctx.contains("内容[1]"));
    }

    @Test
    void buildContextTruncatesContentBeyondTokenBudget() {
        String huge = "x".repeat(60000);
        List<ChatMessage> history = List.of(message("user", huge));

        String ctx = contextManager.buildContext(history, null, null);

        assertEquals(50000 + "\n...(上下文过长已截断)".length(), ctx.length());
        assertTrue(ctx.endsWith("\n...(上下文过长已截断)"));
    }

    @Test
    void needsSummaryOnlyAfterThreshold() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(List.of()));
        assertFalse(contextManager.needsSummary(descendingHistory(15)));
        assertTrue(contextManager.needsSummary(descendingHistory(16)));
    }

    @Test
    void getOldMessagesReturnsOnlyMessagesBeyondRecentWindow() {
        List<ChatMessage> history = descendingHistory(12);

        List<ChatMessage> old = contextManager.getOldMessages(history);

        assertEquals(2, old.size());
        assertEquals(history.get(10), old.get(0));
        assertEquals(history.get(11), old.get(1));
        assertTrue(contextManager.getOldMessages(descendingHistory(10)).isEmpty());
        assertTrue(contextManager.getOldMessages(null).isEmpty());
    }

    @Test
    void buildContextReversesRecentWindowToChronologicalOrder() {
        List<ChatMessage> history = descendingHistory(12);

        String ctx = contextManager.buildContext(history, Collections.emptyList(), null);

        assertTrue(ctx.indexOf("内容[3]") < ctx.indexOf("内容[12]"));
    }
}
