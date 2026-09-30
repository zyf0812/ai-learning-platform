package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 是 sendMessage / sendMessageStream 两条消息流（含刚修复的流式 500 bug 路径）
 * 共同依赖的上下文组装工具，且为纯逻辑无外部依赖，此处覆盖其边界与排序行为。
 */
class ContextManagerTest {

    private final ContextManager cm = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    // ---- buildContext：空输入与 null 安全 ----

    @Test
    void buildContextWithAllNullReturnsEmptyString() {
        String ctx = cm.buildContext(null, null, null);
        assertNotNull(ctx);
        assertTrue(ctx.isEmpty(), "全空输入应返回空字符串而非抛异常");
    }

    @Test
    void buildContextWithEmptyInputsReturnsEmptyString() {
        String ctx = cm.buildContext(List.of(), List.of(), "");
        assertTrue(ctx.isEmpty());
    }

    // ---- buildContext：分段组装 ----

    @Test
    void buildContextWithRagChunksFormatsAsBulletedKnowledge() {
        String ctx = cm.buildContext(null, List.of("片段A", "片段B"), null);
        assertTrue(ctx.contains("【相关知识】"), "应包含相关知识标题");
        assertTrue(ctx.contains("· 片段A"));
        assertTrue(ctx.contains("· 片段B"));
        assertFalse(ctx.contains("【对话背景】"), "无摘要时不应出现对话背景段");
        assertFalse(ctx.contains("【最近对话】"), "无历史时不应出现最近对话段");
    }

    @Test
    void buildContextWithSummaryInjectsBackgroundSection() {
        String ctx = cm.buildContext(null, null, "这是摘要");
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("这是摘要"));
    }

    @Test
    void buildContextReversesHistoryFromDescToAsc() {
        // history 约定为 DESC（最新在前），输出应反转为 ASC（最旧在前）展示
        List<ChatMessage> history = List.of(
            msg("user", "newest"),     // DESC 第 1 条 = 最新
            msg("assistant", "middle"),
            msg("user", "oldest")      // DESC 最后一条 = 最旧
        );
        String ctx = cm.buildContext(history, null, null);

        int newestPos = ctx.indexOf("newest");
        int middlePos = ctx.indexOf("middle");
        int oldestPos = ctx.indexOf("oldest");
        assertTrue(oldestPos < middlePos && middlePos < newestPos,
            "输出应按时间升序排列（oldest 在前，newest 在后）");
    }

    @Test
    void buildContextTruncatesHistoryWindowToRecentCount() {
        // 构造 15 条 DESC 历史，应只保留前 10 条（最近的），并反转
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            history.add(msg("user", "m" + i)); // m1 是最新，m15 是最旧
        }
        String ctx = cm.buildContext(history, null, null);

        // 最近 10 条 = m1..m10，应保留；m11..m15 应被丢弃
        assertTrue(ctx.contains("m1"));
        assertTrue(ctx.contains("m10"));
        assertFalse(ctx.contains("m11"), "超出 RECENT_COUNT 的旧消息不应出现在最近对话段");
        assertFalse(ctx.contains("m15"));
    }

    // ---- buildContext：token 截断边界 ----

    @Test
    void buildContextTrimsOverlongContextWithMarker() {
        // 单个超大 RAG 片段使最终字符串超过 MAX_TOKENS(50000)
        String huge = "x".repeat(60_000);
        String ctx = cm.buildContext(null, List.of(huge), null);
        assertTrue(ctx.endsWith("...(上下文过长已截断)"), "超长上下文应被截断并附标记");
        assertTrue(ctx.length() < 60_000, "截断后应明显短于原始长度");
    }

    @Test
    void buildContextDoesNotTrimWhenUnderLimit() {
        String ctx = cm.buildContext(null, List.of("短片段"), "短摘要");
        assertFalse(ctx.contains("...(上下文过长已截断)"));
    }

    // ---- needsSummary：边界 ----

    @Test
    void needsSummaryFalseForNullOrSmallHistory() {
        assertFalse(cm.needsSummary(null));
        assertFalse(cm.needsSummary(List.of()));
        // RECENT_COUNT=10，阈值是 > 15，故 15 条应返回 false
        List<ChatMessage> fifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) fifteen.add(msg("user", "x"));
        assertFalse(cm.needsSummary(fifteen), "恰好 RECENT_COUNT+5 条不应触发摘要");
    }

    @Test
    void needsSummaryTrueWhenExceedingThreshold() {
        List<ChatMessage> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) sixteen.add(msg("user", "x"));
        assertTrue(cm.needsSummary(sixteen), "超过 RECENT_COUNT+5 条应触发摘要");
    }

    // ---- getOldMessages：边界 ----

    @Test
    void getOldMessagesReturnsEmptyForNullOrSmallHistory() {
        assertTrue(cm.getOldMessages(null).isEmpty());
        assertTrue(cm.getOldMessages(List.of()).isEmpty());
        // 恰好 RECENT_COUNT 条 -> 无旧消息
        List<ChatMessage> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(msg("user", "x"));
        assertTrue(cm.getOldMessages(ten).isEmpty());
    }

    @Test
    void getOldMessagesReturnsTailBeyondRecentCount() {
        // 13 条 DESC：前 10 条为最近窗口，第 11~13（索引 10..12）为待摘要旧消息
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 1; i <= 13; i++) history.add(msg("user", "old" + i));
        List<ChatMessage> old = cm.getOldMessages(history);
        assertEquals(3, old.size(), "应只返回超出最近窗口的 3 条");
        assertEquals("old11", old.get(0).getContent());
        assertEquals("old13", old.get(2).getContent());
    }
}
