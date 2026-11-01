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

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatMapper mapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private DocumentMapper docMapper;
    @Mock private RAGService ragService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(mapper, deepSeek, docMapper, ragService);
    }

    private Conversation conversation() {
        Conversation c = new Conversation();
        c.setId("c1");
        c.setTitle("测试会话");
        c.setUserId("u1");
        return c;
    }

    @Test
    void sendMessageStreamThrowsWhenConversationMissing() throws Exception {
        when(mapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> chatService.sendMessageStream("missing", "q", null, t -> { }));
        assertEquals("会话不存在", ex.getMessage());
        verifyNoInteractions(deepSeek);
        verify(mapper, never()).insertMessage(any());
    }

    @Test
    void sendMessageStreamForwardsTokensAndPersistsBothMessages() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(List.of());
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("Hello");
            onToken.accept(" world");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        chatService.sendMessageStream("c1", "用户问题", null, received::add);

        assertEquals(List.of("Hello", " world"), received);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("用户问题", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("Hello world", saved.get(1).getContent());
    }

    @Test
    void sendMessageStreamDeduplicatesUniformChunksAlreadyReturnedBySearch() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(List.of());
        String chunk = "UNIQUE-CHUNK-" + "x".repeat(80);
        when(ragService.searchByDocument("doc1", "问题", 20))
                .thenReturn(new ArrayList<>(List.of(chunk)));
        when(ragService.getChunksUniform("doc1", 3)).thenReturn(List.of(chunk));
        when(ragService.extractStructure("doc1")).thenReturn("");
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("ok");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "问题", "doc1", t -> { });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), prompt.capture(), any());
        String userPrompt = prompt.getValue();
        assertEquals(userPrompt.indexOf("UNIQUE-CHUNK"), userPrompt.lastIndexOf("UNIQUE-CHUNK"));
    }

    @Test
    void sendMessageStreamContinuesWhenRagSearchFails() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("doc1", "问题", 20))
                .thenThrow(new RuntimeException("embedding service down"));
        when(ragService.getChunksUniform("doc1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("doc1")).thenReturn("");
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("answer");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        assertDoesNotThrow(() -> chatService.sendMessageStream("c1", "问题", "doc1", t -> { }));
        verify(mapper, times(2)).insertMessage(any());
    }

    @Test
    void sendMessageReturnsAnswerAndPersistsMessages() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(deepSeek.chat(anyString(), anyString())).thenReturn("AI 回答");

        String answer = chatService.sendMessage("c1", "问题", null);

        assertEquals("AI 回答", answer);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper, times(2)).insertMessage(captor.capture());
        assertEquals("user", captor.getAllValues().get(0).getRole());
        assertEquals("assistant", captor.getAllValues().get(1).getRole());
    }
}
