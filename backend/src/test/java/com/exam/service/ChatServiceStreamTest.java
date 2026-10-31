package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.ChatMessage;
import com.exam.model.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 近期修复提交（流式接口 500 + REQUIRES_NEW 事务）直接改动 sendMessageStream。
 * 这里锁定：token 透传、用户/AI 消息落库顺序、文档上下文去重、以及 RAG 故障容错。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStreamTest {

    @Mock
    private ChatMapper chatMapper;
    @Mock
    private DeepSeekService deepSeek;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private RAGService ragService;

    private ChatService chatService() {
        return new ChatService(chatMapper, deepSeek, documentMapper, ragService);
    }

    private static Conversation conversation(String id) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId("user-1");
        c.setTitle("测试会话");
        return c;
    }

    @SuppressWarnings("unchecked")
    private static void streamTokens(DeepSeekService mock, String... tokens) throws Exception {
        doAnswer(invocation -> {
            Consumer<String> onToken = (Consumer<String>) invocation.getArgument(2);
            for (String token : tokens) {
                onToken.accept(token);
            }
            return null;
        }).when(mock).chatStream(anyString(), anyString(), any());
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    @Test
    void sendMessageStreamMissingConversationThrows() {
        when(chatMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> chatService().sendMessageStream("missing", "问题", null, token -> { }));

        assertEquals("会话不存在", ex.getMessage());
        verifyNoInteractions(deepSeek);
        verify(chatMapper, never()).insertMessage(any());
    }

    @Test
    void sendMessageStreamForwardsTokensAndPersistsMessagesInOrder() throws Exception {
        when(chatMapper.findById("conv-1")).thenReturn(conversation("conv-1"));
        when(chatMapper.findMessages("conv-1")).thenReturn(List.of());
        streamTokens(deepSeek, "你", "好");

        List<String> received = new ArrayList<>();
        chatService().sendMessageStream("conv-1", "问题", null, received::add);

        assertEquals(List.of("你", "好"), received);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("问题", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("你好", saved.get(1).getContent());
        verify(deepSeek).chatStream(anyString(), anyString(), any());

        InOrder inOrder = inOrder(chatMapper, deepSeek);
        inOrder.verify(chatMapper).insertMessage(saved.get(0));
        inOrder.verify(deepSeek).chatStream(anyString(), anyString(), any());
        inOrder.verify(chatMapper).insertMessage(saved.get(1));
    }

    @Test
    void sendMessageStreamSkipsRagWhenDocumentIdIsEmpty() throws Exception {
        when(chatMapper.findById("conv-1")).thenReturn(conversation("conv-1"));
        when(chatMapper.findMessages("conv-1")).thenReturn(List.of());

        chatService().sendMessageStream("conv-1", "问题", "", token -> { });

        verifyNoInteractions(ragService);
        verify(deepSeek).chatStream(anyString(), anyString(), any());
    }

    @Test
    void sendMessageStreamInjectsDocumentContextAndDeduplicatesChunks() throws Exception {
        when(chatMapper.findById("conv-1")).thenReturn(conversation("conv-1"));
        when(chatMapper.findMessages("conv-1")).thenReturn(List.of());
        when(ragService.searchByDocument("doc-1", "问题", 20))
            .thenReturn(new ArrayList<>(List.of("rag-shared-chunk", "rag-only")));
        when(ragService.getChunksUniform("doc-1", 3))
            .thenReturn(List.of("rag-shared-chunk", "uniform-only"));
        when(ragService.extractStructure("doc-1")).thenReturn("doc-structure");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        chatService().sendMessageStream("conv-1", "问题", "doc-1", token -> { });

        verify(deepSeek).chatStream(anyString(), promptCaptor.capture(), any());
        String prompt = promptCaptor.getValue();

        assertTrue(prompt.contains("【相关知识】"));
        assertTrue(prompt.contains("【文档结构概览】"));
        assertTrue(prompt.contains("doc-structure"));
        assertTrue(prompt.contains("rag-only"));
        assertTrue(prompt.contains("uniform-only"));
        assertEquals(1, occurrences(prompt, "rag-shared-chunk"));
    }

    @Test
    void sendMessageStreamToleratesRagFailure() throws Exception {
        when(chatMapper.findById("conv-1")).thenReturn(conversation("conv-1"));
        when(chatMapper.findMessages("conv-1")).thenReturn(List.of());
        when(ragService.searchByDocument("doc-1", "问题", 20))
            .thenThrow(new RuntimeException("embedding service down"));
        when(ragService.getChunksUniform("doc-1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("doc-1")).thenReturn("");

        assertDoesNotThrow(() ->
            chatService().sendMessageStream("conv-1", "问题", "doc-1", token -> { }));

        verify(chatMapper, times(2)).insertMessage(any());
        verify(deepSeek).chatStream(anyString(), anyString(), any());
    }

    @Test
    void sendMessageStreamCompressesLongHistoryIntoSummary() throws Exception {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 16; i >= 1; i--) {
            ChatMessage m = new ChatMessage();
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("消息[" + i + "]");
            history.add(m);
        }
        when(chatMapper.findById("conv-1")).thenReturn(conversation("conv-1"));
        when(chatMapper.findMessages("conv-1")).thenReturn(history);
        when(deepSeek.chat(anyString(), anyString())).thenReturn("历史摘要");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        chatService().sendMessageStream("conv-1", "问题", null, token -> { });

        verify(deepSeek, times(1)).chat(anyString(), anyString());
        verify(deepSeek).chatStream(anyString(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getValue().contains("【对话背景】"));
        assertTrue(promptCaptor.getValue().contains("历史摘要"));
    }
}
