package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 {@link ContextManager} —— 被 {@link ChatService#sendMessageStream} 与
 * {@link ChatService#sendMessage} 共享的上下文组装工具。
 *
 * 该类为纯逻辑组件（无 Spring 依赖），承担窗口截断、DESC→ASC 反转、token 预算截断、
 * 摘要阈值等关键边界判断，此前无任何测试覆盖。边界错误会直接影响流式对话的上下文
 * 正确性，故补齐确定性单元测试。
 */
class ContextManagerTest {

    private final ContextManager cm = new ContextManager();

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private List<ChatMessage> messages(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(msg("user", "x"));
        return list;
    }

    @Test
    void buildContextWithNullInputsReturnsEmpty() {
        assertEquals("", cm.buildContext(null, null, null));
    }

    @Test
    void buildContextReversesHistoryFromDescToAscAndCapsRecentCount() {
        // history 为 DESC（最新在前）：m11 最新，m00 最旧；用两位补零命名避免子串互相命中
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) history.add(msg("user", "m" + String.format("%02d", 11 - i)));

        String ctx = cm.buildContext(history, null, null);

        // 仅保留最近 10 条（m02..m11），并反转为 ASC（m02 在前，m11 在后）
        int idxOldest = ctx.indexOf("m02");
        int idxNewest = ctx.indexOf("m11");
        assertTrue(idxOldest >= 0 && idxNewest >= 0, "应保留最近 10 条消息");
        assertTrue(idxOldest < idxNewest, "应将 DESC 反转为 ASC 展示");
        assertFalse(ctx.contains("m00"), "超出最近 10 条的旧消息不应出现在最近对话段");
        assertFalse(ctx.contains("m01"), "超出最近 10 条的旧消息不应出现在最近对话段");
    }

    @Test
    void buildContextOrdersRagThenSummaryThenHistory() {
        List<ChatMessage> history = List.of(msg("user", "hist"));
        String ctx = cm.buildContext(history, List.of("chunk1"), "背景摘要");

        int ragIdx = ctx.indexOf("【相关知识】");
        int summaryIdx = ctx.indexOf("【对话背景】");
        int histIdx = ctx.indexOf("【最近对话】");
        assertTrue(ragIdx >= 0 && summaryIdx >= 0 && histIdx >= 0);
        assertTrue(ragIdx < summaryIdx, "RAG 知识应排在摘要之前");
        assertTrue(summaryIdx < histIdx, "摘要应排在最近对话之前");
        assertTrue(ctx.contains("· chunk1"));
        assertTrue(ctx.contains("背景摘要"));
    }

    @Test
    void buildContextTruncatesWhenExceedingTokenBudget() {
        // MAX_TOKENS = 50000，超过则截断并追加提示
        String big = "X".repeat(60_000);
        String ctx = cm.buildContext(null, List.of(big), null);
        String suffix = "\n...(上下文过长已截断)";

        assertEquals(50_000 + suffix.length(), ctx.length());
        assertTrue(ctx.endsWith(suffix));
    }

    @Test
    void needsSummaryReturnsFalseAtOrBelowThreshold() {
        // 阈值 = RECENT_COUNT + 5 = 15，> 15 才需要摘要
        assertFalse(cm.needsSummary(null));
        assertFalse(cm.needsSummary(new ArrayList<>()));
        assertFalse(cm.needsSummary(messages(15)));
    }

    @Test
    void needsSummaryReturnsTrueAboveThreshold() {
        assertTrue(cm.needsSummary(messages(16)));
    }

    @Test
    void getOldMessagesReturnsEmptyAtOrBelowRecentCount() {
        assertTrue(cm.getOldMessages(null).isEmpty());
        assertTrue(cm.getOldMessages(messages(10)).isEmpty(), "恰好 RECENT_COUNT 应返回空");
    }

    @Test
    void getOldMessagesReturnsTailBeyondRecentCount() {
        // size 12 → 返回索引 10..11（2 条），即最近 10 条之外的旧消息
        List<ChatMessage> old = cm.getOldMessages(messages(12));
        assertEquals(2, old.size());
    }
}
