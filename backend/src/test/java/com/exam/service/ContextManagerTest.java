package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 是 ChatService（流式接口 bug fix 涉及模块）上下文装配的核心纯逻辑组件，
 * 负责窗口管理、摘要触发阈值与 token 截断。原仓库无任何覆盖。
 */
class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    // ===== needsSummary 阈值（RECENT_COUNT + 5 = 15）=====

    @Test
    void needsSummaryFalseAtThreshold() {
        // 恰好 15 条：> 15 为假 → 不需要摘要
        assertFalse(manager.needsSummary(messages(15)));
    }

    @Test
    void needsSummaryTrueAboveThreshold() {
        // 16 条：> 15 → 需要摘要
        assertTrue(manager.needsSummary(messages(16)));
    }

    @Test
    void needsSummaryFalseForNull() {
        assertFalse(manager.needsSummary(null));
    }

    // ===== getOldMessages（超出最近 RECENT_COUNT=10 的旧消息）=====

    @Test
    void getOldMessagesReturnsTailBeyondRecentCount() {
        List<ChatMessage> history = messages(25);
        List<ChatMessage> old = manager.getOldMessages(history);
        // 25 条 → 返回 subList(10, 25) = 15 条
        assertEquals(15, old.size());
    }

    @Test
    void getOldMessagesEmptyWhenAtOrBelowRecentCount() {
        assertEquals(0, manager.getOldMessages(messages(10)).size());
        assertEquals(0, manager.getOldMessages(messages(5)).size());
    }

    @Test
    void getOldMessagesEmptyForNull() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    // ===== buildContext 装配顺序与内容 =====

    @Test
    void buildContextAssemblesRagSummaryAndHistoryInOrder() {
        ChatMessage m1 = msg("user", "旧问题");
        ChatMessage m2 = msg("assistant", "新回答");
        // history 为 DESC：m2 是最近的
        List<ChatMessage> history = List.of(m2, m1);

        String ctx = manager.buildContext(history, List.of("片段A"), "历史摘要");

        // 顺序：相关知识 → 对话背景 → 最近对话（反转成 ASC：旧问题 → 新回答）
        int ragIdx = ctx.indexOf("【相关知识】");
        int summaryIdx = ctx.indexOf("【对话背景】");
        int recentIdx = ctx.indexOf("【最近对话】");
        assertTrue(ragIdx >= 0 && ragIdx < summaryIdx && summaryIdx < recentIdx,
            "上下文应按 RAG → 摘要 → 最近对话 排序");
        assertTrue(ctx.contains("· 片段A"));
        assertTrue(ctx.contains("历史摘要"));
        // 反转后旧问题在前
        assertTrue(ctx.indexOf("旧问题") < ctx.indexOf("新回答"));
    }

    @Test
    void buildContextReversesHistoryToAscendingOrder() {
        // history DESC：[m3(最近), m2, m1(最早)]
        ChatMessage m1 = msg("user", "first");
        ChatMessage m2 = msg("assistant", "second");
        ChatMessage m3 = msg("user", "third");
        List<ChatMessage> history = List.of(m3, m2, m1);

        String ctx = manager.buildContext(history, null, null);

        // 反转成 ASC：first → second → third
        assertTrue(ctx.indexOf("first") < ctx.indexOf("second"));
        assertTrue(ctx.indexOf("second") < ctx.indexOf("third"));
    }

    @Test
    void buildContextCapsRecentHistoryToTenMessages() {
        // 25 条历史（> RECENT_COUNT=10）→ 仅保留最近 10 条
        List<ChatMessage> history = messages(25);

        String ctx = manager.buildContext(history, null, null);

        // 第 1-10 条（最近）应出现，第 11 条起（更旧）不应出现
        assertTrue(ctx.contains("msg-0"));
        assertTrue(ctx.contains("msg-9"));
        assertFalse(ctx.contains("msg-10"));
        assertFalse(ctx.contains("msg-24"));
    }

    @Test
    void buildContextHandlesAllEmptyInputs() {
        String ctx = manager.buildContext(null, null, null);
        assertEquals("", ctx);
    }

    @Test
    void buildContextOmitsSectionsWhenEmptyButKeepsOthers() {
        ChatMessage m = msg("user", "hi");
        // ragChunks 空、summary 空，仅历史
        String ctx = manager.buildContext(List.of(m), List.of(), "");
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("hi"));
    }

    // ===== token 截断（MAX_TOKENS=50000）=====

    @Test
    void buildContextTruncatesWhenExceedingTokenBudget() {
        // 构造一个超过 50000 字符的 RAG 片段
        String huge = "A".repeat(60_000);
        String ctx = manager.buildContext(null, List.of(huge), null);

        assertTrue(ctx.length() < 60_000, "超长上下文应被截断");
        assertTrue(ctx.endsWith("...(上下文过长已截断)"),
            "截断后应附带截断标记");
        // 截断阈值 50000：原文含 "【相关知识】\n· " 前缀 + 大量 A，截断后仍保留长串 A
        assertTrue(ctx.contains("A".repeat(100)));
        // 截断点为 50000 字符 + 标记后缀
        assertEquals(50000 + "\n...(上下文过长已截断)".length(), ctx.length());
    }

    @Test
    void buildContextDoesNotTruncateShortText() {
        String ctx = manager.buildContext(null, List.of("短片段"), null);
        assertFalse(ctx.contains("...(上下文过长已截断)"));
    }

    private static ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** 生成 n 条消息，content 为 msg-{index}，index 越小代表越新（DESC） */
    private static List<ChatMessage> messages(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(msg("user", "msg-" + i));
        }
        return list;
    }
}
