package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 是每次对话都会经过的共享组件，负责上下文的裁剪、排序与注入，
 * 之前没有任何测试覆盖。这里重点覆盖窗口边界、历史截断和上下文组装顺序。
 */
class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** history 约定为 DESC：索引 0 是最新消息 */
    private List<ChatMessage> historyDesc(int count) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(message("user", "msg-" + (count - 1 - i)));
        }
        return history;
    }

    @Test
    void emptyInputsProduceEmptyContext() {
        assertEquals("", contextManager.buildContext(null, null, null));
        assertEquals("", contextManager.buildContext(List.of(), List.of(), ""));
    }

    @Test
    void needsSummaryOnlyWhenHistoryExceedsThreshold() {
        assertFalse(contextManager.needsSummary(null));
        assertFalse(contextManager.needsSummary(historyDesc(15)));
        assertTrue(contextManager.needsSummary(historyDesc(16)));
    }

    @Test
    void getOldMessagesReturnsOnlyMessagesBeyondRecentWindow() {
        List<ChatMessage> history = historyDesc(13);
        List<ChatMessage> old = contextManager.getOldMessages(history);

        assertEquals(3, old.size());
        assertEquals("msg-2", old.get(0).getContent());
        assertEquals("msg-1", old.get(1).getContent());
        assertEquals("msg-0", old.get(2).getContent());
    }

    @Test
    void getOldMessagesIsEmptyAtWindowBoundary() {
        assertEquals(List.of(), contextManager.getOldMessages(null));
        assertEquals(List.of(), contextManager.getOldMessages(historyDesc(10)));
    }

    @Test
    void buildContextKeepsTenMostRecentMessagesInAscendingOrder() {
        String context = contextManager.buildContext(historyDesc(13), null, null);

        assertTrue(context.contains("user: msg-3\n"));
        assertTrue(context.contains("user: msg-12\n"));
        assertFalse(context.contains("user: msg-2\n"));
        assertTrue(context.indexOf("msg-3") < context.indexOf("msg-12"));
        assertFalse(context.contains("【相关知识】"));
        assertFalse(context.contains("【对话背景】"));
    }

    @Test
    void buildContextInjectsRagChunksBeforeSummaryAndRecentHistory() {
        String context = contextManager.buildContext(
            List.of(message("user", "最近问题")),
            List.of("文档片段A"),
            "历史摘要B");

        assertTrue(context.contains("【相关知识】\n· 文档片段A"));
        assertTrue(context.contains("【对话背景】\n历史摘要B"));
        assertTrue(context.contains("【最近对话】\nuser: 最近问题"));

        int rag = context.indexOf("【相关知识】");
        int summary = context.indexOf("【对话背景】");
        int recent = context.indexOf("【最近对话】");
        assertEquals(-1, context.indexOf("【相关知识】", rag + 1));
        assertTrue(rag < summary && summary < recent);
    }

    @Test
    void buildContextTruncatesAtTokenBudget() {
        String summary = "x".repeat(60000);
        String context = contextManager.buildContext(null, null, summary);

        String suffix = "\n...(上下文过长已截断)";
        assertEquals(50000 + suffix.length(), context.length());
        assertTrue(context.endsWith(suffix));
    }
}
