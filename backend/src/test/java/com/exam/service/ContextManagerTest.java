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

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(java.util.UUID.randomUUID().toString().substring(0, 8));
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    // --- needsSummary 测试 ---

    @Test
    void needsSummaryReturnsFalseForSmallHistory() {
        assertFalse(manager.needsSummary(null));
        assertFalse(manager.needsSummary(List.of()));
        // RECENT_COUNT = 10, 阈值 = 10 + 5 = 15，所以15条及以下不需要摘要
        List<ChatMessage> h14 = new ArrayList<>();
        for (int i = 0; i < 15; i++) h14.add(msg("user", "q" + i));
        assertFalse(manager.needsSummary(h14));
    }

    @Test
    void needsSummaryReturnsTrueForLargeHistory() {
        // 16条及以上需要摘要
        List<ChatMessage> h16 = new ArrayList<>();
        for (int i = 0; i < 16; i++) h16.add(msg("user", "q" + i));
        assertTrue(manager.needsSummary(h16));
    }

    // --- getOldMessages 测试 ---

    @Test
    void getOldMessagesReturnsEmptyForSmallHistory() {
        assertTrue(manager.getOldMessages(null).isEmpty());
        assertTrue(manager.getOldMessages(List.of()).isEmpty());

        List<ChatMessage> h10 = new ArrayList<>();
        for (int i = 0; i < 10; i++) h10.add(msg("user", "q" + i));
        // RECENT_COUNT = 10，size <= 10 返回空
        assertTrue(manager.getOldMessages(h10).isEmpty());
    }

    @Test
    void getOldMessagesReturnsCorrectSubList() {
        List<ChatMessage> h25 = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ChatMessage m = msg(i % 2 == 0 ? "user" : "assistant", "msg" + i);
            h25.add(m);
        }
        // 按DESC传入（最新的在前）
        List<ChatMessage> old = manager.getOldMessages(h25);
        // 应返回从 index 10 开始的 15 条旧消息
        assertEquals(15, old.size());
        // 第一条旧消息应该是 index 10 的
        assertEquals("msg10", old.get(0).getContent());
    }

    // --- buildContext 测试 ---

    @Test
    void buildContextIncludesRagChunks() {
        List<String> rag = List.of("知识片段1：Java是OOP语言", "知识片段2：Spring是IoC容器");
        String ctx = manager.buildContext(List.of(), rag, null);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("知识片段1"));
        assertTrue(ctx.contains("知识片段2"));
    }

    @Test
    void buildContextIncludesSummary() {
        String ctx = manager.buildContext(List.of(), List.of(), "之前对话讨论了Spring AOP和事务管理。");
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("Spring AOP"));
    }

    @Test
    void buildContextIncludesRecentHistory() {
        // 构造 DESC 顺序的历史（最新在前）
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("assistant", "回答3")); // 最新
        history.add(msg("user", "问题3"));
        history.add(msg("assistant", "回答2"));
        history.add(msg("user", "问题2"));
        history.add(msg("assistant", "回答1"));
        history.add(msg("user", "问题1")); // 最旧

        String ctx = manager.buildContext(history, List.of(), null);

        assertTrue(ctx.contains("【最近对话】"));
        // 最近对话应按ASC顺序（最旧的问题1在前）
        int i1 = ctx.indexOf("问题1");
        int i3 = ctx.indexOf("问题3");
        assertTrue(i1 >= 0 && i3 >= 0);
        assertTrue(i1 < i3, "问题1应出现在问题3之前(ASC顺序)");
        assertTrue(ctx.contains("user: 问题1"));
        assertTrue(ctx.contains("assistant: 回答3"));
    }

    @Test
    void buildContextOnlyKeepsRecentNMessages() {
        // 构造 50 条消息的 DESC 历史
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 49; i >= 0; i--) {
            history.add(msg(i % 2 == 0 ? "user" : "assistant", "msg-content-" + i));
        }
        assertEquals(50, history.size());

        String ctx = manager.buildContext(history, List.of(), null);

        // 最近的是 0-9（因为传入时49是列表第一个，是最新的msg0在DESC最后）
        // 实际：RECENT_COUNT=10，取 history[0..9] 即 msg-content-49 到 msg-content-40
        // 然后反转展示
        assertFalse(ctx.contains("msg-content-0"), "最旧的消息不应出现在最近对话中");
        assertFalse(ctx.contains("msg-content-5"), "较旧的消息不应出现在最近对话中");
        assertTrue(ctx.contains("msg-content-49"), "最新的消息应在最近对话中");
    }

    @Test
    void buildContextWithAllComponents() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("assistant", "answer"));
        history.add(msg("user", "question"));

        List<String> rag = List.of("文档片段：Spring Boot自动配置");
        String summary = "之前讨论了Spring基础。";

        String ctx = manager.buildContext(history, rag, summary);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
        assertTrue(ctx.contains("Spring Boot自动配置"));
        assertTrue(ctx.contains("Spring基础"));
        assertTrue(ctx.contains("user: question"));
    }

    @Test
    void buildContextWithNullInputs() {
        // 全部为 null 不应抛错
        String ctx = manager.buildContext(null, null, null);
        assertNotNull(ctx);
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextWithEmptyInputs() {
        String ctx = manager.buildContext(List.of(), List.of(), "");
        assertNotNull(ctx);
        assertFalse(ctx.contains("【对话背景】")); // 空字符串不算有摘要
    }

    @Test
    void buildContextTrimsVeryLongContent() {
        // 构造一个超长内容（超过 MAX_TOKENS=50000）
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 10000; i++) huge.append("这是一段很长的文本。");
        String content = huge.toString();
        assertTrue(content.length() > 50000);

        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("user", content));

        String ctx = manager.buildContext(history, List.of(), null);
        // 应被截断
        assertTrue(ctx.length() <= 50000 + 30); // + 截断提示
        assertTrue(ctx.endsWith("(上下文过长已截断)") || ctx.length() < 50000);
    }

    @Test
    void buildContextShortContentNotTruncated() {
        String shortText = "短内容";
        List<ChatMessage> history = List.of(msg("user", shortText));
        String ctx = manager.buildContext(history, List.of(), null);
        assertFalse(ctx.contains("(上下文过长已截断)"));
    }

    @Test
    void buildContextReversesRecentMessagesToAscOrder() {
        // DESC: 新 -> 旧
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("assistant", "最新回答"));   // 最新 (index 0)
        history.add(msg("user", "最新问题"));
        history.add(msg("assistant", "较早回答"));
        history.add(msg("user", "较早问题"));        // 最旧 (index 3)

        String ctx = manager.buildContext(history, List.of(), null);

        // ASC 顺序应展示为: 较早问题 -> 较早回答 -> 最新问题 -> 最新回答
        int idxOldQ = ctx.indexOf("较早问题");
        int idxOldA = ctx.indexOf("较早回答");
        int idxNewQ = ctx.indexOf("最新问题");
        int idxNewA = ctx.indexOf("最新回答");

        assertTrue(idxOldQ < idxOldA, "较早问题在较早回答之前");
        assertTrue(idxOldA < idxNewQ, "较早回答在最新问题之前");
        assertTrue(idxNewQ < idxNewA, "最新问题在最新回答之前");
    }
}
