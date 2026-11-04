package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.ChatMessage;
import com.exam.model.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceStreamTest {

    @Mock private ChatMapper mapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private DocumentMapper docMapper;
    @Mock private RAGService ragService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(mapper, deepSeek, docMapper, ragService);
    }

    private Conversation conversation(String id) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId("u1");
        return c;
    }

    @Test
    void sendMessageStreamForwardsTokensAndPersistsOrderedMessages() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation("c1"));
        when(mapper.findMessages("c1")).thenReturn(List.of());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onToken = inv.getArgument(2);
            onToken.accept("你好");
            onToken.accept("，世界");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> tokens = new ArrayList<>();
        chatService.sendMessageStream("c1", "什么是封装", null, tokens::add);

        assertEquals(List.of("你好", "，世界"), tokens);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("什么是封装", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("你好，世界", saved.get(1).getContent());

        InOrder order = inOrder(mapper);
        order.verify(mapper).insertMessage(argThat(m -> "user".equals(m.getRole())));
        order.verify(mapper).insertMessage(argThat(m -> "assistant".equals(m.getRole())));
    }

    @Test
    void sendMessageStreamIncludesDeduplicatedRagChunksInPrompt() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation("c1"));
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "问题", 20)).thenReturn(new ArrayList<>(List.of("chunk A")));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(new ArrayList<>(List.of("chunk A", "chunk B")));
        when(ragService.extractStructure("d1")).thenReturn("【文档开头】\n概览");
        doAnswer(inv -> null).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "问题", "d1", token -> {});

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), prompt.capture(), any());
        String value = prompt.getValue();
        assertTrue(value.contains("【文档结构概览】"));
        assertTrue(value.contains("chunk B"));
        assertEquals(1, value.split("chunk A", -1).length - 1, "重复片段应只保留一次");
    }

    @Test
    void sendMessageStreamRejectsUnknownConversationWithoutSaving() {
        when(mapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("missing", "问题", null, token -> {}));

        assertEquals("会话不存在", ex.getMessage());
        verify(mapper, never()).insertMessage(any(ChatMessage.class));
        verifyNoInteractions(deepSeek);
    }

    @Test
    void sendMessageStreamRunsInNewTransaction() throws Exception {
        var method = ChatService.class.getMethod("sendMessageStream",
            String.class, String.class, String.class, Consumer.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertNotNull(tx, "流式方法必须声明事务，否则新线程中保存消息会失败");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
    }
}
