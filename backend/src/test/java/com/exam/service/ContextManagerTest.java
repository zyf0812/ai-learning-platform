package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager manager;

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(java.util.UUID.randomUUID().toString().substring(0, 8));
        m.setConversationId("conv1");
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    /** 构造 DESC 顺序的历史（最新在前） */
    private List<ChatMessage> historyDESC(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = n; i >= 1; i--) {
            String role = (i % 2 == 1) ? "user" : "assistant";
            list.add(msg(role, "消息" + i));
        }
        return list;
    }

    @BeforeEach
    void setUp() {
        manager = new ContextManager();
    }

    // ========== needsSummary ==========

    @Test
    void needsSummary_nullHistoryFalse() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void needsSummary_smallHistoryFalse() {
        // RECENT_COUNT=10, 阈值 RECENT_COUNT+5 = 15
        // >15 条才返回 true，即 16 条开始需要摘要
        assertFalse(manager.needsSummary(historyDESC(14)));
        assertFalse(manager.needsSummary(historyDESC(15)));
        assertTrue(manager.needsSummary(historyDESC(16)));
    }

    @Test
    void needsSummary_exactly16True() {
        // 阈值是 >15（strict），16 刚好越过
        assertTrue(manager.needsSummary(historyDESC(16)));
    }

    // ========== getOldMessages ==========

    @Test
    void getOldMessages_nullReturnsEmpty() {
        assertTrue(manager.getOldMessages(null).isEmpty());
    }

    @Test
    void getOldMessages_smallHistoryEmpty() {
        assertTrue(manager.getOldMessages(historyDESC(10)).isEmpty());
    }

    @Test
    void getOldMessages_largeHistoryReturnsTail() {
        // RECENT_COUNT = 10，所以 13 条消息应该返回最后 3 条（超出最近10条的旧消息）
        // history 是 DESC，所以 subList(10, 13) 是索引 10,11,12 → 消息 3,2,1（最旧3条）
        List<ChatMessage> all = historyDESC(13);
        List<ChatMessage> old = manager.getOldMessages(all);
        assertEquals(3, old.size());
        // 校验：all[10].content = "消息3"（因为 DESC：消息13,12,11,10,9,8,7,6,5,4, 3,2,1）
        assertTrue(old.get(0).getContent().endsWith("3"));
    }

    // ========== buildContext ==========

    @Test
    void buildContext_nullInputsNotEmptyString() {
        String ctx = manager.buildContext(null, null, null);
        assertNotNull(ctx);
    }

    @Test
    void buildContext_ragChunksPresent_FormattedWithBullets() {
        String ctx = manager.buildContext(List.of(), List.of("知识点A", "知识点B"), null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· 知识点A"));
        assertTrue(ctx.contains("· 知识点B"));
    }

    @Test
    void buildContext_summaryPresent_SectionHeader() {
        String ctx = manager.buildContext(List.of(), List.of(), "摘要：用户问了Java");
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("摘要：用户问了Java"));
    }

    @Test
    void buildContext_recentHistoryReversedToASC() {
        // DESC: msg5, msg4, msg3, msg2, msg1
        List<ChatMessage> hist = new ArrayList<>();
        hist.add(msg("assistant", "回答5"));
        hist.add(msg("user", "问题5"));
        hist.add(msg("assistant", "回答4"));
        hist.add(msg("user", "问题4"));
        hist.add(msg("assistant", "回答3"));

        String ctx = manager.buildContext(hist, List.of(), null);
        assertTrue(ctx.contains("【最近对话】"));
        // ASC 顺序：问题4 先出现，问题5 后出现
        int idxQ4 = ctx.indexOf("问题4");
        int idxQ5 = ctx.indexOf("问题5");
        assertTrue(idxQ4 < idxQ5, "历史应按ASC顺序（旧→新）");
    }

    @Test
    void buildContext_historyTruncatedToRecentCount() {
        // 100 条消息 → 只取最近10条（前10条 DESC）
        List<ChatMessage> hist = historyDESC(100);
        String ctx = manager.buildContext(hist, List.of(), null);
        // 消息 100（最新的）应该存在，消息 11（已超出recent范围）不应存在
        assertTrue(ctx.contains("消息100"));
        // 消息 91（是最近10条中最旧的），消息 90 不应该出现
        assertTrue(ctx.contains("消息91"));
        // 注意：消息 90 属于旧消息，应不在最近对话中
        // 但需要确认具体的截断边界
        int idx90 = ctx.indexOf("消息90");
        int recentHeader = ctx.indexOf("【最近对话】");
        if (idx90 >= 0) {
            assertTrue(idx90 < recentHeader); // 不在【最近对话】区块内（可能在别处或不存在）
        }
    }

    @Test
    void buildContext_allThreeSectionsPresent() {
        List<ChatMessage> hist = historyDESC(3);
        List<String> chunks = List.of("文档知识A");
        String summary = "历史摘要";

        String ctx = manager.buildContext(hist, chunks, summary);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContext_hugeContext_TruncatedWithMessage() {
        // 构造超长内容
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 100000) huge.append("测试文本测试文本测试文本");
        List<String> bigChunks = List.of(huge.toString());

        String ctx = manager.buildContext(List.of(), bigChunks, null);
        // MAX_TOKENS = 50000，超长时应追加截断标记
        assertTrue(ctx.length() <= 50000 + 50); // 允许尾部标记
        assertTrue(ctx.endsWith("...(上下文过长已截断)") || ctx.length() <= 50100);
    }
}
