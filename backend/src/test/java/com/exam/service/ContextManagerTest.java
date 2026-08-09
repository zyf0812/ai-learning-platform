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

    @BeforeEach
    void setUp() {
        manager = new ContextManager();
    }

    private ChatMessage make(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    /**
     * 构造 DESC 顺序的消息列表（最新在前），与 DB 查询顺序一致
     */
    private List<ChatMessage> descMessages(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            String role = (i % 2 == 0) ? "user" : "assistant";
            list.add(make(role, "消息" + i));
        }
        return list;
    }

    // ==================== needsSummary() 阈值判断 ====================

    @Test
    void needsSummaryForSmallHistoryFalse() {
        // RECENT_COUNT = 10, 阈值 = 10+5 = 15
        assertFalse(manager.needsSummary(List.of()));
        assertFalse(manager.needsSummary(descMessages(5)));
        assertFalse(manager.needsSummary(descMessages(10)));
        assertFalse(manager.needsSummary(descMessages(14)));
        assertFalse(manager.needsSummary(descMessages(15))); // 15 == 10+5, 不大于 → false
    }

    @Test
    void needsSummaryForLargeHistoryTrue() {
        assertTrue(manager.needsSummary(descMessages(16))); // > 15
        assertTrue(manager.needsSummary(descMessages(50)));
    }

    @Test
    void needsSummaryNullHistoryFalse() {
        assertFalse(manager.needsSummary(null));
    }

    // ==================== getOldMessages() ====================

    @Test
    void getOldMessagesSmallHistoryReturnsEmpty() {
        assertEquals(List.of(), manager.getOldMessages(null));
        assertEquals(List.of(), manager.getOldMessages(List.of()));
        assertEquals(List.of(), manager.getOldMessages(descMessages(5)));
        assertEquals(List.of(), manager.getOldMessages(descMessages(10)));
    }

    @Test
    void getOldMessagesReturnsOnlyOldPortion() {
        // 15 条消息：索引 0-9 是最近10条，10-14 是"旧消息"
        List<ChatMessage> history = descMessages(15);
        List<ChatMessage> old = manager.getOldMessages(history);
        assertEquals(5, old.size());
        // 在原 DESC 列表中，旧消息应该是 subList(10, 15) → 消息索引 10,11,12,13,14
        // 最老的消息应该是 "消息0"（因为descMessages是降序构造的）
        assertTrue(old.get(old.size() - 1).getContent().endsWith("0"));
    }

    @Test
    void getOldMessagesManyReturnsCorrectCount() {
        List<ChatMessage> history = descMessages(100);
        List<ChatMessage> old = manager.getOldMessages(history);
        assertEquals(90, old.size()); // 100 - 最近10 = 90 条旧消息
    }

    // ==================== buildContext() - 基本结构 ====================

    @Test
    void buildContextEmptyInputsStillWorks() {
        String ctx = manager.buildContext(List.of(), List.of(), null);
        assertNotNull(ctx);
        assertTrue(ctx.isEmpty() || ctx.contains("最近对话"));
    }

    @Test
    void buildContextNullInputsTolerated() {
        // 不抛 NPE
        assertDoesNotThrow(() -> manager.buildContext(null, null, null));
    }

    @Test
    void buildContextIncludesRAGChunks() {
        List<String> chunks = List.of(
                "文档片段A：Java是面向对象语言",
                "文档片段B：JVM负责内存管理"
        );

        String ctx = manager.buildContext(List.of(), chunks, null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· 文档片段A：Java是面向对象语言"));
        assertTrue(ctx.contains("· 文档片段B：JVM负责内存管理"));
    }

    @Test
    void buildContextIncludesSummary() {
        String ctx = manager.buildContext(List.of(), List.of(),
                "之前对话讨论了Spring的DI和AOP概念，用户想了解Bean生命周期。");

        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("Spring的DI和AOP"));
    }

    // ==================== buildContext() - 消息顺序 ====================

    @Test
    void buildContextRecentMessagesShownInChronologicalOrder() {
        // DESC 顺序: [消息4, 消息3, 消息2, 消息1, 消息0]
        // 反转 ASC: [消息0, 消息1, 消息2, 消息3, 消息4]
        List<ChatMessage> history = descMessages(5);

        String ctx = manager.buildContext(history, List.of(), null);

        assertTrue(ctx.contains("【最近对话】"));
        // ASC 顺序：消息0 应该比 消息1 先出现
        int idx0 = ctx.indexOf("消息0");
        int idx1 = ctx.indexOf("消息1");
        int idx4 = ctx.indexOf("消息4");
        assertTrue(idx0 < idx1);
        assertTrue(idx1 < idx4);
    }

    @Test
    void buildContextIncludesRolePrefixes() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(make("assistant", "A回复")); // 最新
        history.add(make("user", "U问题"));      // 较旧

        String ctx = manager.buildContext(history, List.of(), null);
        assertTrue(ctx.contains("user: U问题"));
        assertTrue(ctx.contains("assistant: A回复"));
        // 顺序：反转后 user 在前
        int userIdx = ctx.indexOf("user: U问题");
        int asstIdx = ctx.indexOf("assistant: A回复");
        assertTrue(userIdx < asstIdx);
    }

    // ==================== buildContext() - 只保留最近 N 条 ====================

    @Test
    void buildContextTruncatesToRecentCount() {
        // 共 25 条，只保留最近 10 条（消息 15-24）
        List<ChatMessage> history = descMessages(25);

        String ctx = manager.buildContext(history, List.of(), "摘要");

        // 最近 10 条（消息 15,16,17,18,19,20,21,22,23,24）应该存在
        for (int i = 15; i < 25; i++) {
            assertTrue(ctx.contains("消息" + i), "缺少消息" + i);
        }
        // 旧消息（消息 0-14）不应出现在 【最近对话】 部分
        // 注意：这些可能存在于摘要中，但我们不传摘要时就不应有
        String recentSection = ctx.substring(ctx.indexOf("【最近对话】"));
        assertFalse(recentSection.contains("消息0"));
        assertFalse(recentSection.contains("消息5"));
        assertFalse(recentSection.contains("消息14"));
    }

    // ==================== buildContext() - 综合场景 ====================

    @Test
    void buildContextAllSectionsTogetherCorrectOrder() {
        List<String> chunks = List.of("知识库片段内容");
        String summary = "之前对话摘要：用户询问了算法基础。";
        List<ChatMessage> history = new ArrayList<>();
        history.add(make("assistant", "最新回答"));
        history.add(make("user", "最新问题"));

        String ctx = manager.buildContext(history, chunks, summary);

        // 正确顺序：RAG → 摘要 → 最近对话
        int ragIdx = ctx.indexOf("【相关知识】");
        int summaryIdx = ctx.indexOf("【对话背景】");
        int recentIdx = ctx.indexOf("【最近对话】");

        assertTrue(ragIdx < summaryIdx);
        assertTrue(summaryIdx < recentIdx);
    }

    @Test
    void buildContextRagAndSummaryBothEmptyStillShowsRecent() {
        List<ChatMessage> history = descMessages(3);
        String ctx = manager.buildContext(history, List.of(), null);

        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
    }

    // ==================== buildContext() - token 截断 ====================

    @Test
    void buildContextVeryLongTextGetsTruncatedWithMarker() {
        // 构造超长内容：一条消息就 60000 字符，远超 MAX_TOKENS = 50000
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60000; i++) sb.append('A');
        String longContent = sb.toString();

        List<ChatMessage> history = new ArrayList<>();
        history.add(make("user", longContent));

        String ctx = manager.buildContext(history, List.of(), null);

        // 长度应被截取至 MAX_TOKENS + 标记文本
        assertTrue(ctx.length() <= 50100);
        assertTrue(ctx.endsWith("...(上下文过长已截断)") || ctx.contains("...(上下文过长已截断)"));
    }

    @Test
    void buildContextShortTextNoTruncationMarker() {
        String ctx = manager.buildContext(descMessages(3), List.of(), null);
        assertFalse(ctx.contains("...(上下文过长已截断)"));
    }
}
