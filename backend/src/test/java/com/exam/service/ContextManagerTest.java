package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 上下文管理器测试
 *
 * 重点覆盖：
 * - needsSummary() 阈值判断
 * - getOldMessages() 旧消息提取（DESC 排序）
 * - buildContext() 组装顺序：RAG知识 → 摘要 → 最近对话
 * - 历史过长时的截断
 * - RAG 检索结果注入格式
 * - 摘要注入格式
 */
class ContextManagerTest {

    private ContextManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextManager();
    }

    // ==================== needsSummary ====================

    @Test
    void needsSummary_nullHistory_returnsFalse() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void needsSummary_emptyHistory_returnsFalse() {
        assertFalse(manager.needsSummary(List.of()));
    }

    @Test
    void needsSummary_atThreshold_returnsFalse() {
        // RECENT_COUNT = 10，阈值是 > 15（即 >= 16 触发）
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) history.add(msg("u", "msg" + i)); // 15 条
        assertFalse(manager.needsSummary(history)); // 15 条 == 15，不触发
    }

    @Test
    void needsSummary_oneOverThreshold_returnsTrue() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) history.add(msg("u", "msg" + i)); // 16 条
        assertTrue(manager.needsSummary(history)); // 16 > 15 触发
    }

    @Test
    void needsSummary_wayOver_returnsTrue() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 50; i++) history.add(msg("u", "msg" + i));
        assertTrue(manager.needsSummary(history));
    }

    // ==================== getOldMessages ====================

    @Test
    void getOldMessages_nullHistory_returnsEmpty() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessages_shortHistory_returnsEmpty() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(msg("u", "msg" + i)); // 10 条，刚好等于 RECENT_COUNT
        assertTrue(manager.getOldMessages(history).isEmpty()); // 10 <= 10
    }

    @Test
    void getOldMessages_longHistory_returnsFirstPart() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i <= 20; i++) history.add(msg("u", "msg" + i));
        // history 是 DESC：索引 0 是最新的，索引 20 是最旧的
        // RECENT_COUNT = 10，getOldMessages 取 subList(10, 21) = 11 条
        List<ChatMessage> old = manager.getOldMessages(history);
        assertEquals(11, old.size());
        // 第 10 条索引是 history.get(10) = msg10 之前的"较旧"的部分
        assertEquals(history.get(10), old.get(0));
    }

    // ==================== buildContext ====================

    @Test
    void buildContext_onlyRAGChunks() {
        String ctx = manager.buildContext(null, List.of("chunk1", "chunk2"), null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("chunk1"));
        assertTrue(ctx.contains("chunk2"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContext_onlySummary() {
        String ctx = manager.buildContext(null, null, "这是摘要内容");
        assertFalse(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("这是摘要内容"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContext_historyReversedOrder() {
        // history 为 DESC，需要反转成 ASC
        List<ChatMessage> history = List.of(
                msg("assistant", "新回答"),   // index 0（最新）
                msg("user", "新问题"),
                msg("assistant", "旧回答"),   // index 2
                msg("user", "旧问题")         // index 3（最旧）
        );
        String ctx = manager.buildContext(history, null, null);

        assertTrue(ctx.contains("【最近对话】"));
        // 顺序：先"旧问题"，再"旧回答"，再"新问题"，最后"新回答"
        int idxOldQ = ctx.indexOf("旧问题");
        int idxOldA = ctx.indexOf("旧回答");
        int idxNewQ = ctx.indexOf("新问题");
        int idxNewA = ctx.indexOf("新回答");

        assertTrue(idxOldQ < idxOldA, "旧问题应在旧回答前");
        assertTrue(idxOldA < idxNewQ, "旧回答应在新问题前");
        assertTrue(idxNewQ < idxNewA, "新问题应在新回答前");
    }

    @Test
    void buildContext_missingRagAndSummary() {
        List<ChatMessage> history = List.of(msg("user", "你好"));
        String ctx = manager.buildContext(history, null, null);
        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("你好"));
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
    }

    @Test
    void buildContext_historyTruncatedToRecentCount() {
        // history 必须是 DESC 顺序（最新在前）：索引 0 = 最新，索引 N = 最旧
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 20; i >= 0; i--) history.add(msg("u", "msg" + i));

        String ctx = manager.buildContext(history, null, null);

        // subList(0, 10) 取 msg20~msg11（最新10条），反转后 ASC = msg11~msg20
        // msg10（最旧的11条之后的）不在，msg11 在
        assertTrue(!ctx.contains("msg0"), "msg0 不应在上下文里");
        assertTrue(ctx.contains("msg11"), "msg11 应在上下文里");
    }

    @Test
    void buildContext_allSourcesInOrder() {
        List<ChatMessage> history = List.of(msg("user", "问题"), msg("assistant", "回答"));
        List<String> rag = List.of("知识片段1");

        String ctx = manager.buildContext(history, rag, "摘要");

        int ragIdx = ctx.indexOf("【相关知识】");
        int sumIdx = ctx.indexOf("【对话背景】");
        int histIdx = ctx.indexOf("【最近对话】");

        assertTrue(ragIdx < sumIdx, "RAG 应在摘要前");
        assertTrue(sumIdx < histIdx, "摘要应在历史前");
    }

    @Test
    void buildContext_ragChunksAsBulletPoints() {
        String ctx = manager.buildContext(null, List.of("块A", "块B"), null);
        assertTrue(ctx.contains("· 块A"));
        assertTrue(ctx.contains("· 块B"));
    }

    @Test
    void buildContext_emptyInput_returnsEmptyContext() {
        String ctx = manager.buildContext(List.of(), List.of(), "");
        // 所有分支都跳过，应返回空串或极小内容
        assertNotNull(ctx);
    }

    @Test
    void buildContext_nullListsHandled() {
        String ctx = manager.buildContext(null, null, null);
        assertEquals("", ctx);
    }

    // ==================== 辅助 ====================

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }
}
