package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 单元测试：
 *  - needsSummary 阈值判断
 *  - getOldMessages 正确分片
 *  - buildContext 顺序 / 反转 / 片段注入
 *  - trimToTokens 超长截断
 */
class ContextManagerTest {

    private ContextManager cm;

    @BeforeEach
    void setUp() {
        cm = new ContextManager();
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private List<ChatMessage> makeHistory(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = count; i >= 1; i--) { // DESC: 最新在前
            String role = i % 2 == 1 ? "user" : "assistant";
            list.add(msg(role, "[M" + i + "]"));
        }
        return list;
    }

    // ========== needsSummary ==========

    @Test
    void needsSummaryNullFalse() {
        assertFalse(cm.needsSummary(null));
    }

    @Test
    void needsSummaryEmptyFalse() {
        assertFalse(cm.needsSummary(List.of()));
    }

    @Test
    void needsSummaryExactlyAtThresholdFalse() {
        // RECENT_COUNT=10, 阈值 RECENT_COUNT+5=15
        // history.size() > 15 才需要，所以 15 → false
        assertFalse(cm.needsSummary(makeHistory(15)));
    }

    @Test
    void needsSummaryAboveThresholdTrue() {
        assertTrue(cm.needsSummary(makeHistory(16)));
    }

    @Test
    void needsSummaryLargeTrue() {
        assertTrue(cm.needsSummary(makeHistory(100)));
    }

    // ========== getOldMessages ==========

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(cm.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessagesSmallerThanRecentReturnsEmpty() {
        assertTrue(cm.getOldMessages(makeHistory(5)).isEmpty());
    }

    @Test
    void getOldMessagesExactlyRecentReturnsEmpty() {
        assertTrue(cm.getOldMessages(makeHistory(10)).isEmpty());
    }

    @Test
    void getOldMessagesAboveRecentReturnsTail() {
        // 12 条消息，RECENT_COUNT=10 → 第 10~12 条（索引 10,11）= 旧消息
        // 注意 history 是 DESC: [M12],[M11],[M10],[M9],...[M1] (12条)
        // subList(10, 12) → 索引 10=[M2], 11=[M1] → 因为 DESC
        List<ChatMessage> history = makeHistory(12);
        var old = cm.getOldMessages(history);
        assertEquals(2, old.size());
        assertEquals("[M2]", old.get(0).getContent());
        assertEquals("[M1]", old.get(1).getContent());
    }

    @Test
    void getOldMessagesLarge() {
        // 100 条，取索引 10~100 = 90 条
        assertEquals(90, cm.getOldMessages(makeHistory(100)).size());
    }

    // ========== buildContext: 顺序反转 ==========

    @Test
    void buildContextReversesRecentMessagesToAsc() {
        // 3 条 DESC: [M3](最新),[M2],[M1]
        // 反转 ASC: [M1],[M2],[M3]
        List<ChatMessage> h = makeHistory(3);
        String ctx = cm.buildContext(h, List.of(), null);

        int i1 = ctx.indexOf("[M1]");
        int i2 = ctx.indexOf("[M2]");
        int i3 = ctx.indexOf("[M3]");
        assertTrue(i1 >= 0 && i2 >= 0 && i3 >= 0);
        assertTrue(i1 < i2);
        assertTrue(i2 < i3);
    }

    @Test
    void buildContextOnlyTakesRecent10ForLargeHistory() {
        // 25 条 DESC → 只取前 10 条（最新 10 条：[M25]~[M16]）
        // 反转后 ASC：[M16],[M17],...,[M25]
        List<ChatMessage> h = makeHistory(25);
        String ctx = cm.buildContext(h, List.of(), null);

        // 应该出现：[M16]~[M25]（10条）
        for (int i = 16; i <= 25; i++) {
            assertTrue(ctx.contains("[M" + i + "]"), "应包含[M" + i + "]");
        }
        // 不应该出现：[M15] 及更早
        assertFalse(ctx.contains("[M15]"), "不应包含旧[M15]");
        assertFalse(ctx.contains("[M1]"), "不应包含旧[M1]");
    }

    // ========== buildContext: RAG 注入 ==========

    @Test
    void buildContextIncludesRagChunks() {
        String ctx = cm.buildContext(List.of(),
            List.of("chunk1 知识点", "chunk2 知识点"), null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("chunk1 知识点"));
        assertTrue(ctx.contains("chunk2 知识点"));
    }

    @Test
    void buildContextNoRagChunksSkipsSection() {
        String ctx = cm.buildContext(List.of(), List.of(), null);
        assertFalse(ctx.contains("【相关知识】"));
    }

    @Test
    void buildContextNullRagChunksSafe() {
        assertDoesNotThrow(() -> cm.buildContext(List.of(), null, null));
    }

    // ========== buildContext: 摘要注入 ==========

    @Test
    void buildContextIncludesSummary() {
        String ctx = cm.buildContext(List.of(), List.of(), "之前对话的摘要");
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("之前对话的摘要"));
    }

    @Test
    void buildContextEmptySummarySkipped() {
        String ctx = cm.buildContext(List.of(), List.of(), "");
        assertFalse(ctx.contains("【对话背景】"));
    }

    @Test
    void buildContextNullHistorySafe() {
        assertDoesNotThrow(() -> cm.buildContext(null, List.of("chunk"), "sum"));
    }

    // ========== buildContext: 角色内容展示 ==========

    @Test
    void buildContextShowsRolePrefix() {
        List<ChatMessage> h = List.of(
            msg("assistant", "回答"), // DESC: 最新在前
            msg("user", "问题")
        );
        String ctx = cm.buildContext(h, List.of(), null);
        // 反转后：user 在前，assistant 在后
        int userIdx = ctx.indexOf("user: 问题");
        int asstIdx = ctx.indexOf("assistant: 回答");
        assertTrue(userIdx >= 0);
        assertTrue(asstIdx >= 0);
        assertTrue(userIdx < asstIdx);
    }

    // ========== trimToTokens 超长截断 ==========

    @Test
    void buildContextVeryLongTruncates() {
        // 构造一个超长单条消息，超过 MAX_TOKENS (50000)
        String longContent = "X".repeat(60000);
        List<ChatMessage> h = List.of(msg("user", longContent));
        String ctx = cm.buildContext(h, List.of(), null);
        // 应该被截断，且有提示标记
        assertTrue(ctx.length() <= 50000 + 50); // 留点余量给前缀
        assertTrue(ctx.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContextShortNoTruncation() {
        String shortContent = "简短问题";
        List<ChatMessage> h = List.of(msg("user", shortContent));
        String ctx = cm.buildContext(h, List.of(), null);
        assertFalse(ctx.contains("(上下文过长已截断)"));
        assertTrue(ctx.contains("简短问题"));
    }

    // ========== 综合 ==========

    @Test
    void buildContextFullOrdering() {
        // RAG + Summary + History 都存在时，顺序应为：
        // 1.【相关知识】2.【对话背景】3.【最近对话】
        String ctx = cm.buildContext(
            makeHistory(3),
            List.of("知识chunk"),
            "对话摘要"
        );
        int ragIdx = ctx.indexOf("【相关知识】");
        int sumIdx = ctx.indexOf("【对话背景】");
        int hisIdx = ctx.indexOf("【最近对话】");
        assertTrue(ragIdx >= 0 && sumIdx >= 0 && hisIdx >= 0);
        assertTrue(ragIdx < sumIdx);
        assertTrue(sumIdx < hisIdx);
    }
}
