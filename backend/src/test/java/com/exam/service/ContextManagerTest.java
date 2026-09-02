package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        return m;
    }

    // ---------- buildContext：空输入 ----------

    @Test
    void buildContextAllNullsReturnsEmpty() {
        String ctx = cm.buildContext(null, null, null);
        assertTrue(ctx.isEmpty(), "空上下文应返回空字符串，实际: [" + ctx + "]");
    }

    @Test
    void buildContextEmptyCollectionsReturnsEmpty() {
        String ctx = cm.buildContext(new ArrayList<>(), new ArrayList<>(), "");
        assertTrue(ctx.isEmpty());
    }

    // ---------- buildContext：RAG 片段 ----------

    @Test
    void buildContextIncludesRagChunks() {
        List<String> chunks = List.of("Java是一种面向对象语言", "Spring框架支持IoC");
        String ctx = cm.buildContext(new ArrayList<>(), chunks, null);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("Java是一种面向对象语言"));
        assertTrue(ctx.contains("Spring框架支持IoC"));
        assertTrue(ctx.contains("· ")); // 前缀标记
    }

    // ---------- buildContext：摘要 ----------

    @Test
    void buildContextIncludesSummary() {
        String summary = "用户此前询问了数据库事务和ACID特性";
        String ctx = cm.buildContext(new ArrayList<>(), new ArrayList<>(), summary);

        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains(summary));
    }

    // ---------- buildContext：最近对话顺序（反转 DESC → ASC） ----------

    @Test
    void buildContextReversesRecentHistoryToAscOrder() {
        // 传入按 DESC（最新在前）排列的历史消息
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("assistant", "回复3")); // 最新
        history.add(msg("user", "提问3"));
        history.add(msg("assistant", "回复2"));
        history.add(msg("user", "提问2"));
        history.add(msg("assistant", "回复1"));
        history.add(msg("user", "提问1")); // 最早

        String ctx = cm.buildContext(history, new ArrayList<>(), null);

        assertTrue(ctx.contains("【最近对话】"));
        // 输出顺序应为 ASC：user:提问1 → assistant:回复1 → ...
        int idx1 = ctx.indexOf("user: 提问1");
        int idx2 = ctx.indexOf("assistant: 回复1");
        int idx3 = ctx.indexOf("user: 提问2");
        int idx4 = ctx.indexOf("assistant: 回复3");

        assertTrue(idx1 >= 0, "应包含提问1");
        assertTrue(idx2 >= 0, "应包含回复1");
        assertTrue(idx3 >= 0, "应包含提问2");
        assertTrue(idx4 >= 0, "应包含回复3");
        assertTrue(idx1 < idx2 && idx2 < idx3 && idx3 < idx4,
                "对话顺序应为时间升序，实际索引: " + idx1 + "," + idx2 + "," + idx3 + "," + idx4);
    }

    // ---------- buildContext：超量历史只取最近 N 条 ----------

    @Test
    void buildContextOnlyKeepsRecent10Messages() {
        // 构造 20 条消息（DESC），应只取前 10 条（最新 10 条）
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 20; i >= 1; i--) {
            history.add(msg("user", "msg" + i)); // msg20 最新，msg1 最早
        }

        String ctx = cm.buildContext(history, new ArrayList<>(), null);

        assertTrue(ctx.contains("msg20"), "最新消息应保留");
        assertTrue(ctx.contains("msg11"), "第10新的消息应保留");
        assertFalse(ctx.contains("msg10"), "第11新的消息不应保留");
        assertFalse(ctx.contains("msg1"), "最早的消息不应保留");
    }

    // ---------- buildContext：多部分组合顺序 ----------

    @Test
    void buildContextOrderIsRagThenSummaryThenHistory() {
        List<String> chunks = List.of("知识片段");
        String summary = "对话摘要";
        List<ChatMessage> history = List.of(msg("user", "最近消息"));

        String ctx = cm.buildContext(history, chunks, summary);

        int ragIdx = ctx.indexOf("【相关知识】");
        int summaryIdx = ctx.indexOf("【对话背景】");
        int recentIdx = ctx.indexOf("【最近对话】");

        assertTrue(ragIdx >= 0 && summaryIdx >= 0 && recentIdx >= 0);
        assertTrue(ragIdx < summaryIdx && summaryIdx < recentIdx,
                "顺序应为RAG→摘要→最近对话，索引: " + ragIdx + "," + summaryIdx + "," + recentIdx);
    }

    // ---------- needsSummary ----------

    @Test
    void needsSummaryNullReturnsFalse() {
        assertFalse(cm.needsSummary(null));
    }

    @Test
    void needsSummaryBelowThresholdReturnsFalse() {
        // 阈值 = RECENT_COUNT(10) + 5 = 15
        List<ChatMessage> l = new ArrayList<>();
        for (int i = 0; i < 15; i++) l.add(msg("u", "m" + i)); // 刚好 15 条
        assertFalse(cm.needsSummary(l), "15条不应触发摘要");
    }

    @Test
    void needsSummaryAboveThresholdReturnsTrue() {
        List<ChatMessage> l = new ArrayList<>();
        for (int i = 0; i < 16; i++) l.add(msg("u", "m" + i)); // 16 条 → 超过 15
        assertTrue(cm.needsSummary(l), "16条应触发摘要");
    }

    @Test
    void needsSummaryEmptyReturnsFalse() {
        assertFalse(cm.needsSummary(new ArrayList<>()));
    }

    // ---------- getOldMessages ----------

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertTrue(cm.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessagesShorterThanRecentCountReturnsEmpty() {
        List<ChatMessage> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) l.add(msg("u", "m" + i)); // 刚好 10 条
        assertTrue(cm.getOldMessages(l).isEmpty());
    }

    @Test
    void getOldMessagesReturnsExcessBeyondRecent10() {
        List<ChatMessage> l = new ArrayList<>();
        // 12 条：[0..9] 是最近 10 条 (newest)，[10,11] 为旧消息
        for (int i = 0; i < 12; i++) {
            l.add(msg("u", "msg" + i));
        }

        List<ChatMessage> old = cm.getOldMessages(l);
        assertEquals(2, old.size());
        assertEquals("msg10", old.get(0).getContent());
        assertEquals("msg11", old.get(1).getContent());
    }

    // ---------- trimToTokens（buildContext 的边界）----------

    @Test
    void buildContextVeryLargeContentGetsTruncated() {
        // MAX_TOKENS = 50000，构造超过此长度的上下文
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) { // 每条 ~10 字 × 6000 = 60000+
            sb.append("这是一个知识库内容片段。");
        }
        List<String> largeChunks = List.of(sb.toString());

        String ctx = cm.buildContext(new ArrayList<>(), largeChunks, null);

        // 结果应 ≤ MAX_TOKENS + 截断标记长度，不应无限膨胀
        assertTrue(ctx.length() > 50000, "内容应超过 50000 字符才说明输入很大");
        // 实际 trimToTokens 是按 length (char) 截断
        assertTrue(ctx.contains("上下文过长已截断"), "超长内容应附加截断标记");
    }

    @Test
    void buildContextShortContentNotTruncated() {
        String shortText = "简短内容";
        String ctx = cm.buildContext(new ArrayList<>(), List.of(shortText), null);
        assertFalse(ctx.contains("上下文过长已截断"));
    }
}
