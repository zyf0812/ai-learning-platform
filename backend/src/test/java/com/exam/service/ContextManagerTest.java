package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 测试：覆盖上下文组装、摘要触发条件、旧消息提取、
 * 消息顺序反转、token 截断等核心路径。
 *
 * ContextManager 是 ChatService 的下游共享工具，直接影响 AI 回答质量。
 * 错误的顺序或截断会导致对话上下文错乱，产生幻觉回答。
 */
class ContextManagerTest {

    private ContextManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextManager();
    }

    // ========== needsSummary 阈值判断 ==========

    @Test
    void needsSummary_returnsFalse_forNullHistory() {
        assertFalse(manager.needsSummary(null), "null history 不应触发摘要");
    }

    @Test
    void needsSummary_returnsFalse_forEmptyHistory() {
        assertFalse(manager.needsSummary(List.of()), "空 history 不应触发摘要");
    }

    @Test
    void needsSummary_returnsFalse_whenBelowThreshold() {
        // RECENT_COUNT=10, threshold=RECENT_COUNT+5=15
        List<ChatMessage> hist = buildMessages(14);
        assertFalse(manager.needsSummary(hist), "14 条消息 (<=15) 不应触发摘要");
    }

    @Test
    void needsSummary_returnsTrue_whenAboveThreshold() {
        List<ChatMessage> hist = buildMessages(16);
        assertTrue(manager.needsSummary(hist), "16 条消息 (>15) 应触发摘要");
    }

    @Test
    void needsSummary_returnsFalse_atBoundaryExact() {
        // 15 条 = RECENT_COUNT(10) + 5, 刚好等于阈值，不触发
        List<ChatMessage> hist = buildMessages(15);
        assertFalse(manager.needsSummary(hist), "边界 15 条不应触发摘要");
    }

    // ========== getOldMessages 旧消息提取 ==========

    @Test
    void getOldMessages_returnsEmpty_forNullHistory() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessages_returnsEmpty_whenNoExcess() {
        List<ChatMessage> hist = buildMessages(5);
        assertTrue(manager.getOldMessages(hist).isEmpty(),
            "消息数 <= RECENT_COUNT 时不应有旧消息");
    }

    @Test
    void getOldMessages_returnsCorrectExcess() {
        // 13 条消息：buildMessages(13) 以 DESC 顺序生成:
        //   index 0 -> "msg-12" (最新), index 1 -> "msg-11", ..., index 12 -> "msg-0" (最旧)
        // RECENT_COUNT=10, getOldMessages 返回 subList(10, 13) = index 10,11,12
        //   即旧消息: "msg-2", "msg-1", "msg-0" (注意是最旧的 3 条)
        List<ChatMessage> hist = buildMessages(13);
        List<ChatMessage> old = manager.getOldMessages(hist);
        assertEquals(3, old.size(), "13-10=3 条旧消息");
        assertEquals("msg-2", old.get(0).getContent(), "index 10 对应第三旧消息");
        assertEquals("msg-0", old.get(2).getContent(), "index 12 对应最旧消息");
    }

    // ========== buildContext 上下文组装 ==========

    @Test
    void buildContext_withAllInputs_containsThreeSections() {
        List<ChatMessage> hist = buildMessages(3);
        List<String> rag = List.of("文档片段A内容", "文档片段B内容");
        String summary = "这是一段对话摘要";

        String ctx = manager.buildContext(hist, rag, summary);

        assertTrue(ctx.contains("【相关知识】"), "应包含 RAG 片段区块");
        assertTrue(ctx.contains("文档片段A内容"), "应包含具体 RAG 内容");
        assertTrue(ctx.contains("【对话背景】"), "应包含摘要区块");
        assertTrue(ctx.contains("这是一段对话摘要"), "应包含具体摘要内容");
        assertTrue(ctx.contains("【最近对话】"), "应包含最近对话区块");
    }

    @Test
    void buildContext_withoutRagAndSummary_onlyShowsRecentDialog() {
        List<ChatMessage> hist = buildMessages(2);

        String ctx = manager.buildContext(hist, null, null);

        assertFalse(ctx.contains("【相关知识】"), "无 RAG 时不应出现该区块标题");
        assertFalse(ctx.contains("【对话背景】"), "无摘要时不应出现该区块标题");
        assertTrue(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContext_recentDialogIsInAscOrder() {
        // history 为 DESC（msg-2, msg-1, msg-0），buildContext 应反转为 ASC 展示
        List<ChatMessage> hist = buildMessages(3);
        assertEquals("msg-2", hist.get(0).getContent()); // 确认 DESC 基准

        String ctx = manager.buildContext(hist, List.of(), null);

        int idx0 = ctx.indexOf("msg-0");
        int idx1 = ctx.indexOf("msg-1");
        int idx2 = ctx.indexOf("msg-2");
        assertTrue(idx0 < idx1 && idx1 < idx2,
            "对话顺序应为 ASC：msg-0 最先，msg-2 最后 (实际位置: 0@" + idx0 + " 1@" + idx1 + " 2@" + idx2 + ")");
    }

    @Test
    void buildContext_trimsToMaxTokens_whenContentTooLong() {
        // 构造一个远超 MAX_TOKENS(50000) 的 RAG 列表
        String longChunk = "X".repeat(1000);
        List<String> rag = new ArrayList<>();
        for (int i = 0; i < 60; i++) rag.add(longChunk); // 60k chars

        String ctx = manager.buildContext(List.of(), rag, null);

        assertTrue(ctx.endsWith("\n...(上下文过长已截断)"),
            "超长内容应带截断标记");
        assertTrue(ctx.length() <= 50000 + 50, "截断后长度不应显著超过预算");
    }

    @Test
    void buildContext_truncatesExcessHistoryToRecentCount() {
        // 30 条消息（DESC），只保留最近 10 条在【最近对话】区
        List<ChatMessage> hist = buildMessages(30);

        String ctx = manager.buildContext(hist, List.of(), null);

        // 最近 10 条 (msg-0 到 msg-9 不，DESC 中前 10 条是 msg-20 到 msg-29)
        // 反转 ASC 后，对话中最新的是 msg-29
        assertTrue(ctx.contains("msg-29"), "最近的 msg-29 应出现在对话中");
        assertTrue(ctx.contains("msg-20"), "刚好进入最近 10 条边界的 msg-20 应出现");
        assertFalse(ctx.contains("msg-15"), "超出最近 10 条的旧消息不应出现在【最近对话】区");
    }

    // ========== 空输入鲁棒性 ==========

    @Test
    void buildContext_handlesAllNullAndEmpty() {
        assertDoesNotThrow(() -> manager.buildContext(null, null, null));
        assertDoesNotThrow(() -> manager.buildContext(List.of(), List.of(), ""));
    }

    // ========== 辅助方法 ==========

    /**
     * 构建指定数量的消息，内容为 "msg-{idx}"，按 DESC 顺序排列
     * （即索引 0 是最新消息，对应 idx 最大）
     */
    private List<ChatMessage> buildMessages(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            ChatMessage m = new ChatMessage();
            m.setId("m" + i);
            m.setContent("msg-" + i);
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setCreatedAt(LocalDateTime.now());
            list.add(m);
        }
        return list;
    }
}
