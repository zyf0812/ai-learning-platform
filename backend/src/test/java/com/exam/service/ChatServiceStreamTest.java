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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 覆盖最近一次修复的流式接口（REQUIRES_NEW 事务传播）核心行为：
 * token 透传、用户消息先于 AI 回答落库、RAG 片段去重、RAG 故障降级、长历史摘要。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStreamTest {

    @Mock private ChatMapper mapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private DocumentMapper docMapper;
    @Mock private RAGService ragService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(mapper, deepSeek, docMapper, ragService);
    }

    private static Conversation conversation(String id) {
        Conversation c = new Conversation();
        c.setId(id);
        return c;
    }

    private static ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void streamForwardsTokensAndPersistsMessagesInOrder() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation("c1"));
        when(mapper.findMessages("c1")).thenReturn(List.of(message("user", "上一轮问题")));
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = inv.getArgument(2);
            onToken.accept("Hel");
            onToken.accept("lo");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        service.sendMessageStream("c1", "什么是多态？", "", received::add);

        assertEquals(List.of("Hel", "lo"), received, "每个 token 都必须原样推送给前端");

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper, times(2)).insertMessage(captor.capture());
        ChatMessage userMsg = captor.getAllValues().get(0);
        ChatMessage assistantMsg = captor.getAllValues().get(1);

        assertEquals("user", userMsg.getRole());
        assertEquals("什么是多态？", userMsg.getContent());
        assertEquals("assistant", assistantMsg.getRole());
        assertEquals("Hello", assistantMsg.getContent(), "落库回答应为完整拼接结果");
        assertNotNull(userMsg.getCreatedAt());
        assertNotNull(assistantMsg.getCreatedAt());
        verifyNoInteractions(ragService, docMapper);
    }

    @Test
    void missingConversationAbortsBeforeCallingModel() {
        when(mapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.sendMessageStream("missing", "hi", null, t -> {}));

        assertEquals("会话不存在", ex.getMessage());
        verifyNoInteractions(deepSeek);
        verify(mapper, never()).insertMessage(any());
    }

    @Test
    void ragResultsAreDeduplicatedUsingChunkPrefix() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation("c1"));
        when(mapper.findMessages("c1")).thenReturn(List.of());

        String vectorHit = "Z".repeat(60);
        String samePrefix = "Z".repeat(55) + "-DUPLICATE-TAIL";
        when(ragService.searchByDocument("d1", "问题", 20)).thenReturn(new ArrayList<>(List.of(vectorHit)));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of(samePrefix, "UNIQUE-CHUNK"));
        when(ragService.extractStructure("d1")).thenReturn("【章节标题】\n章节A");
        doNothing().when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "问题", "d1", t -> {});

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), prompt.capture(), any());

        assertTrue(prompt.getValue().contains(vectorHit), "向量检索命中的片段必须保留");
        assertTrue(prompt.getValue().contains("UNIQUE-CHUNK"), "均匀采样中的新片段必须补充进上下文");
        assertFalse(prompt.getValue().contains("-DUPLICATE-TAIL"), "与前缀重复的片段应被去重");
        assertTrue(prompt.getValue().startsWith("【文档结构概览】\n【章节标题】\n章节A"), "文档结构应置于上下文最前");
    }

    @Test
    void ragFailureFallsBackToUniformChunksAndStillStreams() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation("c1"));
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "问题", 20)).thenThrow(new RuntimeException("向量服务不可用"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of("FALLBACK-CHUNK"));
        when(ragService.extractStructure("d1")).thenReturn("");
        doNothing().when(deepSeek).chatStream(anyString(), anyString(), any());

        assertDoesNotThrow(() -> service.sendMessageStream("c1", "问题", "d1", t -> {}));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), prompt.capture(), any());
        assertTrue(prompt.getValue().contains("FALLBACK-CHUNK"));
    }

    @Test
    void longHistoryIsSummarizedAndInjectedIntoPrompt() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation("c1"));
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) history.add(message("user", "old-" + i));
        when(mapper.findMessages("c1")).thenReturn(history);
        when(deepSeek.chat(anyString(), anyString())).thenReturn("压缩后的摘要");
        doNothing().when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "新问题", null, t -> {});

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), prompt.capture(), any());
        assertTrue(prompt.getValue().contains("【对话背景】\n压缩后的摘要"));
        // 只保留最近 10 条原文，最早的 6 条已被摘要替代
        assertFalse(prompt.getValue().contains("old-15"), "摘要覆盖的旧消息不应再出现在最近对话中");
    }
}
