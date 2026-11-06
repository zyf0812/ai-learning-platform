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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatMapper, deepSeek, documentMapper, ragService);
    }

    private Conversation conversation(String id) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setTitle("t");
        c.setUserId("u1");
        return c;
    }

    @Test
    void missingConversationThrowsBeforeStreamingOrPersisting() {
        when(chatMapper.findById("missing")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("missing", "hi", null, token -> {}));

        verify(chatMapper, never()).insertMessage(any(ChatMessage.class));
        verifyNoInteractions(deepSeek);
    }

    @Test
    void streamsTokensAndPersistsUserBeforeAssistant() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(new ArrayList<>());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("Hello");
            onToken.accept(" world");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        chatService.sendMessageStream("c1", "question", null, received::add);

        assertEquals(List.of("Hello", " world"), received);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();

        assertEquals(2, saved.size());
        assertEquals("user", saved.get(0).getRole());
        assertEquals("question", saved.get(0).getContent());
        assertEquals("c1", saved.get(0).getConversationId());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("Hello world", saved.get(1).getContent());
        assertNotNull(saved.get(0).getCreatedAt());
        assertNotNull(saved.get(1).getCreatedAt());
    }

    @Test
    void ragFailureDoesNotAbortStreaming() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(new ArrayList<>());
        when(ragService.searchByDocument(eq("doc1"), anyString(), eq(20)))
            .thenThrow(new RuntimeException("embedding unavailable"));
        when(ragService.getChunksUniform("doc1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("doc1")).thenReturn("");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("ok");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        chatService.sendMessageStream("c1", "question", "doc1", received::add);

        assertEquals(List.of("ok"), received);
        // user message and assistant message are still stored despite RAG failure
        verify(chatMapper, times(2)).insertMessage(any(ChatMessage.class));
    }

    @Test
    void deepSeekFailurePropagatesAndDoesNotStoreAssistantMessage() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(new ArrayList<>());
        doThrow(new IOException("stream broke"))
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        assertThrows(IOException.class,
            () -> chatService.sendMessageStream("c1", "question", null, token -> {}));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(1)).insertMessage(captor.capture());
        assertEquals("user", captor.getValue().getRole());
    }
}
