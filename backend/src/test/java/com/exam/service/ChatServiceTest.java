package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.ChatMessage;
import com.exam.model.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 覆盖 {@link ChatService#sendMessageStream} 流式会话流程。
 *
 * 近期提交（aa0a3c3）为该方法添加了 {@code @Transactional(propagation = REQUIRES_NEW)}，
 * 修复了由 {@code ChatController#stream} 在新线程中调用导致的 500 错误。该方法此前
 * 完全没有测试覆盖，且属于业务关键的 AI 对话流式路径，故补齐以下行为测试。
 *
 * 事务传播本身是 Spring 容器层面的集成行为（依赖真实 DB + 代理），此处沿用项目既有
 * 的 Mockito 单元测试模式（参见 {@code AuthServiceTest}），聚焦可确定验证的业务逻辑：
 * 会话校验、消息持久化顺序、token 回调、RAG 去重与历史摘要注入。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatMapper mapper;
    @Mock
    private DeepSeekService deepSeek;
    @Mock
    private DocumentMapper docMapper;
    @Mock
    private RAGService ragService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(mapper, deepSeek, docMapper, ragService);
    }

    @Test
    void sendMessageStreamThrowsWhenConversationNotFound() {
        when(mapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.sendMessageStream("missing", "q", null, t -> {}));
        assertEquals("会话不存在", ex.getMessage());

        verify(mapper, never()).insertMessage(any());
        verifyNoInteractions(deepSeek, ragService);
    }

    @Test
    void sendMessageStreamWithoutDocumentPersistsUserThenAssistantAndStreamsTokens() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());

        List<String> received = new ArrayList<>();
        Consumer<String> onToken = received::add;
        doAnswer(inv -> {
            Consumer<String> cb = inv.getArgument(2);
            cb.accept("Hello");
            cb.accept(" ");
            cb.accept("world");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "什么是RAG", null, onToken);

        // 每一个 token 都应回调给前端
        assertEquals(List.of("Hello", " ", "world"), received);

        // 用户消息先于 AI 回答持久化，AI 回答为 token 拼接的完整内容
        ArgumentCaptor<ChatMessage> cap = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper, times(2)).insertMessage(cap.capture());
        List<ChatMessage> saved = cap.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("什么是RAG", saved.get(0).getContent());
        assertEquals("c1", saved.get(0).getConversationId());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("Hello world", saved.get(1).getContent());
        assertEquals("c1", saved.get(1).getConversationId());

        // 无 documentId 时不应触发 RAG 与历史摘要
        verifyNoInteractions(ragService);
        verify(deepSeek, never()).chat(anyString(), anyString());
    }

    @Test
    void sendMessageStreamWithDocumentDedupsRagChunksAndPrependsStructure() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());

        // 两个片段共享前 50 字符前缀：uniform 版本应被去重，只保留向量搜索版本
        String dupPrefix = "A".repeat(55);
        when(ragService.searchByDocument("doc1", "问题", 20))
            .thenReturn(List.of(dupPrefix + "SEARCH", "B".repeat(30)));
        when(ragService.getChunksUniform("doc1", 3))
            .thenReturn(List.of(dupPrefix + "UNIFORM", "C".repeat(40)));
        when(ragService.extractStructure("doc1")).thenReturn("STRUCT");

        doAnswer(inv -> null).when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "问题", "doc1", t -> {});

        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), promptCap.capture(), any());
        String prompt = promptCap.getValue();

        assertTrue(prompt.contains("SEARCH"), "应保留向量搜索结果片段");
        assertFalse(prompt.contains("UNIFORM"), "应去重与搜索结果前缀重复的 uniform 片段");
        assertTrue(prompt.contains("B".repeat(30)), "应保留非重复的搜索片段");
        assertTrue(prompt.contains("C".repeat(40)), "应保留非重复的 uniform 片段");
        assertTrue(prompt.contains("【文档结构概览】\nSTRUCT"), "应在上下文前注入文档结构骨架");

        verify(mapper, times(2)).insertMessage(any());
    }

    @Test
    void sendMessageStreamGeneratesSummaryWhenHistoryExceedsThreshold() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(mapper.findById("c1")).thenReturn(conv);

        // 阈值 = RECENT_COUNT(10) + 5 = 15，超过 15 条触发摘要压缩
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            history.add(msg(i % 2 == 0 ? "user" : "assistant", "msg-" + i));
        }
        when(mapper.findMessages("c1")).thenReturn(history);

        when(deepSeek.chat(anyString(), anyString())).thenReturn("SUMMARY_TEXT");
        doAnswer(inv -> null).when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "问题", null, t -> {});

        // 旧消息应被压缩为摘要并注入上下文
        verify(deepSeek).chat(anyString(), anyString());
        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), promptCap.capture(), any());
        assertTrue(promptCap.getValue().contains("【对话背景】\nSUMMARY_TEXT"),
            "应将历史摘要注入上下文");

        verify(mapper, times(2)).insertMessage(any());
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
