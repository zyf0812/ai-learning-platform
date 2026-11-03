package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 是聊天上下文拼装的核心逻辑，被 ChatService 同步/流式两条链路共用。
 * 这里覆盖最近消息截取、摘要注入、RAG 片段注入和超长上下文截断的边界条件。
 */
class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    private static ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** history 按时间倒序（DESC），第 0 条为最新 */
    private static List<ChatMessage> historyOf(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(message("user", "msg-" + i));
        return list;
    }

    @Test
    void buildContextIncludesRagChunksSummaryAndRecentMessages() {
        String ctx = manager.buildContext(
            List.of(message("user", "最新问题"), message("assistant", "最新回答")),
            List.of("文档片段A"),
            "历史摘要");

        assertTrue(ctx.startsWith("【相关知识】\n· 文档片段A\n\n"), ctx);
        assertTrue(ctx.contains("【对话背景】\n历史摘要\n\n"), ctx);
        assertTrue(ctx.contains("【最近对话】\n"), ctx);
    }

    @Test
    void recentMessagesAreLimitedToTenAndShownOldestFirst() {
        List<ChatMessage> history = historyOf(12);
        String ctx = manager.buildContext(history, List.of(), null);

        // msg-0 是最新，msg-9 是保留窗口中最旧的一条；msg-10/11 应被排除
        assertTrue(ctx.contains("user: msg-9"));
        assertTrue(ctx.contains("user: msg-0"));
        assertFalse(ctx.contains("msg-10"), ctx);
        assertFalse(ctx.contains("msg-11"), ctx);
        assertTrue(ctx.indexOf("msg-9") < ctx.indexOf("msg-0"), "应按 ASC（旧->新）顺序展示");
    }

    @Test
    void buildContextHandlesNullAndEmptyInputs() {
        assertEquals("", manager.buildContext(null, null, null));
        assertEquals("", manager.buildContext(List.of(), List.of(), ""));

        String ctx = manager.buildContext(List.of(message("user", "hi")), null, null);
        assertTrue(ctx.contains("user: hi"));
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
    }

    @Test
    void needsSummaryTriggersOnlyAboveFifteenMessages() {
        assertFalse(manager.needsSummary(null));
        assertFalse(manager.needsSummary(List.of()));
        assertFalse(manager.needsSummary(historyOf(15)));
        assertTrue(manager.needsSummary(historyOf(16)));
    }

    @Test
    void getOldMessagesExcludesRecentWindow() {
        assertTrue(manager.getOldMessages(null).isEmpty());
        assertTrue(manager.getOldMessages(historyOf(10)).isEmpty());

        List<ChatMessage> eleven = historyOf(11);
        List<ChatMessage> old = manager.getOldMessages(eleven);
        assertEquals(1, old.size());
        assertEquals("msg-10", old.get(0).getContent(), "应返回窗口之外最旧的消息");

        List<ChatMessage> twenty = historyOf(20);
        assertEquals(10, manager.getOldMessages(twenty).size());
    }

    @Test
    void oversizedContextIsTruncatedAndMarked() {
        List<ChatMessage> history = List.of(message("user", "x".repeat(60_000)));
        String ctx = manager.buildContext(history, List.of(), null);

        assertTrue(ctx.endsWith("\n...(上下文过长已截断)"));
        // 前缀固定内容 + 50000 字符 + 截断标记
        assertTrue(ctx.length() > 50_000);
        assertTrue(ctx.length() < 60_000, "超长上下文必须被裁剪，避免超出模型 token 预算");
    }
}
