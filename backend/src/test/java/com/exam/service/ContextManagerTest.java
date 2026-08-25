package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /**
     * 构造 history 的约定：参数按 "时间从新到旧"（DESC）顺序传入
     * 即第一个参数 = 最新消息，最后一个 = 最早消息
     */
    private List<ChatMessage> historyDesc(ChatMessage... messages) {
        return new ArrayList<>(List.of(messages));
    }

    // ============ buildContext 基础测试 ============

    @Test
    void buildContextWithAllInputs() {
        // DESC 顺序: 最新在前
        List<ChatMessage> history = historyDesc(
            msg("assistant", "回答2"),  // 最新
            msg("user", "问题2"),
            msg("assistant", "回答1"),
            msg("user", "问题1")         // 最早
        );

        List<String> rag = List.of("知识片段A", "知识片段B");
        String summary = "之前讨论了基础概念";

        String ctx = manager.buildContext(history, rag, summary);

        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· 知识片段A"));
        assertTrue(ctx.contains("· 知识片段B"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("之前讨论了基础概念"));
        assertTrue(ctx.contains("【最近对话】"));
        // ASC 展示顺序：从早到晚 -> 问题1, 回答1, 问题2, 回答2
        int idxQ1 = ctx.indexOf("user: 问题1");
        int idxA2 = ctx.indexOf("assistant: 回答2");
        assertTrue(idxQ1 >= 0 && idxA2 >= 0, "两条消息都应该存在");
        assertTrue(idxQ1 < idxA2, "最早的消息(问题1)应出现在最新的消息(回答2)之前");
    }

    @Test
    void buildContextNullInputs() {
        String ctx = manager.buildContext(null, null, null);
        assertNotNull(ctx);
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextEmptyLists() {
        String ctx = manager.buildContext(List.of(), List.of(), "");
        assertNotNull(ctx);
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextOnlyRag() {
        List<String> rag = List.of("片段1");
        String ctx = manager.buildContext(null, rag, null);
        assertTrue(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("· 片段1"));
        assertFalse(ctx.contains("【对话背景】"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextOnlySummary() {
        String ctx = manager.buildContext(null, null, "历史摘要内容");
        assertFalse(ctx.contains("【相关知识】"));
        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("历史摘要内容"));
        assertFalse(ctx.contains("【最近对话】"));
    }

    @Test
    void buildContextOnlyHistory() {
        // DESC: assistant hello (新), user hi (旧)
        List<ChatMessage> history = historyDesc(
            msg("assistant", "hello"),  // index 0: 最新
            msg("user", "hi")             // index 1: 更早
        );

        String ctx = manager.buildContext(history, null, null);
        assertFalse(ctx.contains("【相关知识】"));
        assertFalse(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("【最近对话】"));
        // 反转后 ASC 顺序：先 user: hi (旧)，再 assistant: hello (新)
        int hiIdx = ctx.indexOf("user: hi");
        int helloIdx = ctx.indexOf("assistant: hello");
        assertTrue(hiIdx >= 0 && helloIdx >= 0);
        assertTrue(hiIdx < helloIdx, "较早的消息 user:hi 应在较新的 assistant:hello 之前");
    }

    // ============ buildContext 历史消息窗口测试 ============

    @Test
    void buildContextTrimsToRecentMessages() {
        // 生成 15 条消息，DESC 顺序：最新的在前
        // 命名不含数字子串歧义，使用 A (最新) 到 O (最早)
        String[] labels = {"A","B","C","D","E","F","G","H","I","J","K","L","M","N","O"};
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            history.add(msg("user", "msg-" + labels[i]));
        }
        // history[0] = msg-A (最新), history[9] = msg-J (第10新), history[14] = msg-O (最早)
        assertEquals(15, history.size());

        String ctx = manager.buildContext(history, null, null);

        // RECENT_COUNT=10，保留最新 10 条 = msg-A 到 msg-J
        assertTrue(ctx.contains("msg-A"));   // 最新的，必保留
        assertTrue(ctx.contains("msg-F"));
        assertTrue(ctx.contains("msg-J"));   // 保留窗口中最早的
        // msg-K 到 msg-O 应被截断（超出最近10条）
        assertFalse(ctx.contains("msg-K"));
        assertFalse(ctx.contains("msg-O"));
    }

    // ============ needsSummary 测试 ============

    @Test
    void needsSummaryNullHistory() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void needsSummaryEmptyHistory() {
        assertFalse(manager.needsSummary(List.of()));
    }

    @Test
    void needsSummaryUnderThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        // RECENT_COUNT=10 + 5 = 15，所以 15 条以下不需要摘要
        for (int i = 0; i < 15; i++) {
            history.add(msg("user", "m" + i));
        }
        assertFalse(manager.needsSummary(history));
    }

    @Test
    void needsSummaryAtThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        // 刚好 15 条：不满足条件（needsSummary 是 > 而非 >=）
        for (int i = 0; i < 15; i++) {
            history.add(msg("user", "m" + i));
        }
        assertFalse(manager.needsSummary(history));
    }

    @Test
    void needsSummaryOverThreshold() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            history.add(msg("user", "m" + i));
        }
        assertTrue(manager.needsSummary(history));
    }

    @Test
    void needsSummaryLargeHistory() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            history.add(msg("user", "m" + i));
        }
        assertTrue(manager.needsSummary(history));
    }

    // ============ getOldMessages 测试 ============

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertEquals(List.of(), manager.getOldMessages(null));
    }

    @Test
    void getOldMessagesUnderRecentCountReturnsEmpty() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(msg("user", "m" + i));
        }
        assertEquals(List.of(), manager.getOldMessages(history));
    }

    @Test
    void getOldMessagesExactlyRecentCountReturnsEmpty() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(msg("user", "m" + i));
        }
        assertTrue(manager.getOldMessages(history).isEmpty());
    }

    @Test
    void getOldMessagesReturnsOlderOnes() {
        List<ChatMessage> history = new ArrayList<>();
        // DESC 顺序：msg-A(最新) -> msg-O(最早)
        String[] labels = {"A","B","C","D","E","F","G","H","I","J","K","L","M","N","O"};
        for (int i = 0; i < 15; i++) {
            history.add(msg("user", "msg-" + labels[i]));
        }
        assertEquals(15, history.size());
        // history[0] = msg-A ... history[9] = msg-J ... history[14] = msg-O

        List<ChatMessage> old = manager.getOldMessages(history);
        // RECENT_COUNT=10，所以超出部分是第 10-14 号索引 = msg-K 到 msg-O
        assertEquals(5, old.size());
        assertEquals("msg-K", old.get(0).getContent());
        assertEquals("msg-O", old.get(4).getContent());
    }

    // ============ trimToTokens (内部通过 buildContext 触发) ============

    @Test
    void buildContextTrimsVeryLongContent() {
        StringBuilder sb = new StringBuilder();
        // 生成超过 MAX_TOKENS=50000 字符的内容
        for (int i = 0; i < 50001; i++) {
            sb.append('a');
        }
        String longText = sb.toString();
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg("user", longText));

        String ctx = manager.buildContext(history, null, null);
        assertNotNull(ctx);
        assertTrue(ctx.contains("上下文过长已截断"));
    }
}
