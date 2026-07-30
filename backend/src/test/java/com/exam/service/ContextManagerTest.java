package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 是 ChatService（含流式 sendMessageStream）的核心依赖，
 * 负责拼装 RAG 片段、历史摘要与最近对话，并做 token 截断。
 * 此前无任何测试。这里覆盖其窗口保留、顺序反转、摘要判定与截断边界。
 */
class ContextManagerTest {

    private final ContextManager contextManager = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void buildContextIncludesAllSectionsWhenPresent() {
        List<ChatMessage> history = List.of(msg("user", "q1"), msg("assistant", "a1"));
        List<String> ragChunks = List.of("chunk-A");

        String ctx = contextManager.buildContext(history, ragChunks, "旧对话摘要");

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("chunk-A"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("旧对话摘要"));
        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("user: q1"));
        assertTrue(ctx.contains("assistant: a1"));
    }

    @Test
    void buildContextHandlesNullAndEmptyInputs() {
        String ctx = contextManager.buildContext(null, null, null);
        assertNotNull(ctx);
        // 三个 section 均缺失时，上下文应为空串
        assertEquals("", ctx);

        // 空 list / 空 summary 同样不应产生对应 section
        String ctx2 = contextManager.buildContext(List.of(), List.of(), "");
        assertEquals("", ctx2);
    }

    @Test
    void buildContextKeepsOnlyMostRecentTenMessages() {
        // history 为 DESC（最前为最新），共 12 条 → 只保留前 10 条（最新的）
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(msg("user", "kept-" + i));
        history.add(msg("user", "dropped-a"));
        history.add(msg("user", "dropped-b"));

        String ctx = contextManager.buildContext(history, null, null);

        // 前 10 条（最新）保留，后 2 条（最旧）丢弃
        assertTrue(ctx.contains("kept-0"));
        assertTrue(ctx.contains("kept-9"));
        assertFalse(ctx.contains("dropped-a"));
        assertFalse(ctx.contains("dropped-b"));
    }

    @Test
    void buildContextReversesHistoryToAscendingOrder() {
        // history 传入为 DESC（最新在前），输出应反转为 ASC（最旧在前）
        List<ChatMessage> history = List.of(
            msg("user", "latest"),
            msg("assistant", "middle"),
            msg("user", "earliest")
        );

        String ctx = contextManager.buildContext(history, null, null);

        int earliestPos = ctx.indexOf("earliest");
        int middlePos = ctx.indexOf("middle");
        int latestPos = ctx.indexOf("latest");

        assertTrue(earliestPos < middlePos);
        assertTrue(middlePos < latestPos);
    }

    @Test
    void needsSummaryBoundaryAtRecentCountPlusFive() {
        // RECENT_COUNT=10，阈值 = 10 + 5 = 15
        assertFalse(contextManager.needsSummary(messagesOf(15)));
        assertTrue(contextManager.needsSummary(messagesOf(16)));
        assertFalse(contextManager.needsSummary(null));
    }

    @Test
    void getOldMessagesReturnsOnlyMessagesBeyondRecentWindow() {
        // history 为 DESC：前 10 条为最近窗口，其余为待摘要旧消息
        List<ChatMessage> history = messagesOf(13);

        List<ChatMessage> old = contextManager.getOldMessages(history);

        assertEquals(3, old.size());
        // 返回索引 10、11、12 对应的内容（最旧的 3 条）
        assertEquals("c10", old.get(0).getContent());
        assertEquals("c11", old.get(1).getContent());
        assertEquals("c12", old.get(2).getContent());
    }

    @Test
    void getOldMessagesReturnsEmptyWhenWithinWindow() {
        assertEquals(List.of(), contextManager.getOldMessages(messagesOf(10)));
        assertEquals(List.of(), contextManager.getOldMessages(null));
    }

    @Test
    void buildContextTrimsContextExceedingTokenBudget() {
        // 构造超过 MAX_TOKENS(50000) 的 RAG 片段，验证截断标记
        String big = "x".repeat(60000);
        String ctx = contextManager.buildContext(List.of(), List.of(big), null);

        // 截断后应包含截断标记
        assertTrue(ctx.contains("上下文过长已截断"),
            "expected trim marker, ctx.length=" + ctx.length());
        // 截断后长度不应超过预算 + 标记长度（+1 为前缀 \n）
        assertTrue(ctx.length() <= 50000 + "\n...(上下文过长已截断)".length());
    }

    private List<ChatMessage> messagesOf(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(msg("user", "c" + i));
        return list;
    }
}
