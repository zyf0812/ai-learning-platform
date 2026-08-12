package com.exam.service;

import com.exam.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 测试：覆盖摘要阈值判断、旧消息切片、上下文组装顺序等核心逻辑。
 */
class ContextManagerTest {

    private final ContextManager manager = new ContextManager();

    // ========== needsSummary 阈值判断 ==========

    @Test
    void needsSummaryNullHistoryReturnsFalse() {
        assertFalse(manager.needsSummary(null));
    }

    @Test
    void needsSummaryEmptyListReturnsFalse() {
        assertFalse(manager.needsSummary(List.of()));
    }

    @Test
    void needsSummaryFifteenMessagesShouldNeed() {
        // RECENT_COUNT=10, threshold = RECENT_COUNT+5 = 15
        // 恰好15条时 history.size() > 15 为 false
        List<ChatMessage> list = makeMessages(15);
        assertFalse(manager.needsSummary(list), "15条不应触发（不大于阈值）");
    }

    @Test
    void needsSummarySixteenMessagesShouldNeed() {
        // 16条 > 15 阈值，应触发
        List<ChatMessage> list = makeMessages(16);
        assertTrue(manager.needsSummary(list), "16条应触发摘要");
    }

    @Test
    void needsSummaryLargeHistoryDefinitelyNeeds() {
        assertTrue(manager.needsSummary(makeMessages(50)));
    }

    // ========== getOldMessages 旧消息切片 ==========

    @Test
    void getOldMessagesNullReturnsEmpty() {
        assertEquals(List.of(), manager.getOldMessages(null));
    }

    @Test
    void getOldMessagesSmallerThanRecentCountReturnsEmpty() {
        assertEquals(List.of(), manager.getOldMessages(makeMessages(5)));
    }

    @Test
    void getOldMessagesExactlyRecentCountReturnsEmpty() {
        // RECENT_COUNT = 10，恰好10条不需要摘要
        assertEquals(List.of(), manager.getOldMessages(makeMessages(10)));
    }

    @Test
    void getOldMessagesReturnsMessagesAfterRecentCount() {
        // history 为 DESC，getOldMessages 取 subList(10, end)
        List<ChatMessage> all = makeMessages(20);
        List<ChatMessage> olds = manager.getOldMessages(all);

        // 应得到 20 - 10 = 10 条旧消息
        assertEquals(10, olds.size());
        // 第一条旧消息应是原 list 的 index 10
        assertEquals(all.get(10), olds.get(0));
        assertEquals("m_10", olds.get(0).getContent());
    }

    @Test
    void getOldMessagesLargeHistorySlicesCorrectly() {
        List<ChatMessage> all = makeMessages(100);
        List<ChatMessage> olds = manager.getOldMessages(all);

        assertEquals(90, olds.size());
        assertEquals("m_10", olds.get(0).getContent());
        assertEquals("m_99", olds.get(olds.size() - 1).getContent());
    }

    // ========== buildContext 上下文组装 ==========

    @Test
    void buildContextEmptyEverythingReturnsEmptyOrNearEmpty() {
        String ctx = manager.buildContext(List.of(), List.of(), null);
        // 没有任何内容时，trim 后可能含有截断标记或空
        assertNotNull(ctx);
    }

    @Test
    void buildContextRagChunksIncluded() {
        List<String> chunks = List.of("知识点1：关于AI的介绍", "知识点2：机器学习基础");

        String ctx = manager.buildContext(List.of(), chunks, null);

        assertTrue(ctx.contains("【相关知识】"), "应包含相关知识标记");
        assertTrue(ctx.contains("知识点1：关于AI的介绍"));
        assertTrue(ctx.contains("知识点2：机器学习基础"));
        // 块前应有点前缀
        assertTrue(ctx.contains("· 知识点1"));
    }

    @Test
    void buildContextSummaryIncluded() {
        String ctx = manager.buildContext(List.of(), List.of(),
            "用户之前讨论了Java集合框架中的ArrayList和LinkedList区别");

        assertTrue(ctx.contains("【对话背景】"));
        assertTrue(ctx.contains("ArrayList和LinkedList区别"));
    }

    @Test
    void buildContextRecentMessagesInAscOrder() {
        // 传入 DESC（最近在前）：m_0 最新, m_4 最旧
        List<ChatMessage> hist = makeMessages(5);

        String ctx = manager.buildContext(hist, List.of(), null);

        assertTrue(ctx.contains("【最近对话】"));
        // 输出时应反转成 ASC：m_4 先出现，m_0 后出现
        int idx4 = ctx.indexOf("user: m_4");
        int idx0 = ctx.indexOf("user: m_0");
        assertTrue(idx4 >= 0 && idx0 >= 0, "两条消息都应存在");
        assertTrue(idx4 < idx0,
            "ASC顺序：m_4(旧) 应在 m_0(新) 之前。idx4=" + idx4 + " idx0=" + idx0
                + "\nctx=" + ctx);
    }

    @Test
    void buildContextLargeHistoryTruncatesToRecentCount() {
        // 传入100条，只保留最近(最前面)10条
        List<ChatMessage> hist = makeMessages(100);

        String ctx = manager.buildContext(hist, List.of(), null);

        // m_0 ~ m_9 应存在（最近10条）
        for (int i = 0; i < 10; i++) {
            assertTrue(ctx.contains("m_" + i), "缺少最近消息 m_" + i);
        }
        // m_10 及以后不应出现在"最近对话"中（它们是 getOldMessages 的部分）
        assertFalse(ctx.contains("m_10") && !ctx.contains("m_100"),
            "m_10 不应出现在最近对话中");
    }

    @Test
    void buildContextCombinesAllSections() {
        List<ChatMessage> hist = makeMessages(3);
        List<String> chunks = List.of("文档片段A");
        String summary = "之前的对话摘要";

        String ctx = manager.buildContext(hist, chunks, summary);

        // 三个 section 全部出现，顺序：相关知识 -> 对话背景 -> 最近对话
        int ik = ctx.indexOf("【相关知识】");
        int ib = ctx.indexOf("【对话背景】");
        int ir = ctx.indexOf("【最近对话】");

        assertTrue(ik >= 0);
        assertTrue(ib >= 0);
        assertTrue(ir >= 0);
        assertTrue(ik < ib && ib < ir, "顺序应为 知识->背景->最近对话。ik=" + ik + " ib=" + ib + " ir=" + ir);
    }

    @Test
    void buildContextRolesDisplayedCorrectly() {
        List<ChatMessage> hist = new ArrayList<>();
        ChatMessage u = new ChatMessage();
        u.setRole("user");
        u.setContent("用户的问题");
        hist.add(u);
        ChatMessage a = new ChatMessage();
        a.setRole("assistant");
        a.setContent("AI的回答");
        hist.add(a);

        String ctx = manager.buildContext(hist, List.of(), null);

        assertTrue(ctx.contains("user: 用户的问题"));
        assertTrue(ctx.contains("assistant: AI的回答"));
    }

    @Test
    void buildContextVeryLongInputGetsTruncated() {
        // 构造极长内容确保超过 MAX_TOKENS(50000) 截断
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 60000; i++) longContent.append('x');
        String content = longContent.toString();

        List<String> chunks = List.of(content);
        String ctx = manager.buildContext(List.of(), chunks, null);

        // 应包含截断标记
        assertTrue(ctx.length() <= 50100, "实际长度: " + ctx.length());
        assertTrue(ctx.contains("(上下文过长已截断)"), "应被截断并加标记");
    }

    // ========== helpers ==========

    /**
     * 生成 N 条消息（DESC，index 0 最新，index N-1 最旧）。
     * 内容格式 "m_{i}"，role 交替 user/assistant。
     */
    private List<ChatMessage> makeMessages(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ChatMessage m = new ChatMessage();
            m.setId("msg_" + i);
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("m_" + i); // m_0 最新
            m.setCreatedAt(LocalDateTime.now().minusMinutes(i));
            list.add(m);
        }
        return list;
    }
}
