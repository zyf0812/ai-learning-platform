package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.ChatMessage;
import com.exam.model.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    // === conversation CRUD tests ===

    @Test
    void listConversationsDelegatesToMapper() {
        Conversation c = new Conversation();
        when(mapper.findByUserId("u1")).thenReturn(List.of(c));

        var result = chatService.listConversations("u1");
        assertEquals(1, result.size());
    }

    @Test
    void createConversationSetsIdAndUserId() {
        chatService.createConversation("u1", "Test Chat");

        verify(mapper).insertConv(argThat(c ->
                c.getUserId().equals("u1") && c.getTitle().equals("Test Chat")
        ));
    }

    @Test
    void createConversationDefaultTitle() {
        chatService.createConversation("u1", null);

        verify(mapper).insertConv(argThat(c -> "新对话".equals(c.getTitle())));
    }

    @Test
    void getConversationDelegatesToMapper() {
        Conversation c = new Conversation();
        c.setId("c1");
        when(mapper.findById("c1")).thenReturn(c);

        var result = chatService.getConversation("c1");
        assertEquals("c1", result.getId());
    }

    @Test
    void getMessagesPagedDelegatesToMapper() {
        ChatMessage m = new ChatMessage();
        when(mapper.findMessagesPaged("c1", 20, 0)).thenReturn(List.of(m));

        var result = chatService.getMessagesPaged("c1", 20, 0);
        assertEquals(1, result.size());
    }

    @Test
    void countMessagesDelegatesToMapper() {
        when(mapper.countMessages("c1")).thenReturn(5);
        assertEquals(5, chatService.countMessages("c1"));
    }

    @Test
    void deleteConversationDelegatesToMapper() {
        chatService.deleteConversation("c1");
        verify(mapper).deleteConv("c1");
    }

    // === sendMessage tests ===

    @Test
    void sendMessageConversationNotFoundThrows() {
        when(mapper.findById("c1")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> chatService.sendMessage("c1", "Hello", null));
    }

    @Test
    void sendMessageWithoutDocSkipsRAG() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(deepSeek.chat(anyString(), anyString())).thenReturn("AI response");

        var result = chatService.sendMessage("c1", "Hello", null);

        assertEquals("AI response", result);
        verify(deepSeek).chat(anyString(), anyString());
        verify(mapper, times(2)).insertMessage(any(ChatMessage.class));
    }

    @Test
    void sendMessageWithDocUsesRAG() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "Hello", 20)).thenReturn(List.of("relevant chunk"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("d1")).thenReturn("文档结构");
        when(deepSeek.chat(anyString(), anyString())).thenReturn("AI answer with RAG");

        var result = chatService.sendMessage("c1", "Hello", "d1");

        assertEquals("AI answer with RAG", result);
        verify(ragService).searchByDocument("d1", "Hello", 20);
        verify(ragService).extractStructure("d1");
    }

    @Test
    void sendMessageDeduplicatesRAGChunks() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "Hello", 20))
                .thenReturn(List.of("This is a long enough chunk for dedup testing"));
        when(ragService.getChunksUniform("d1", 3))
                .thenReturn(List.of("This is a long enough chunk for dedup testing"));
        when(ragService.extractStructure("d1")).thenReturn("");
        when(deepSeek.chat(anyString(), anyString())).thenReturn("Answer");

        chatService.sendMessage("c1", "Hello", "d1");

        verify(deepSeek).chat(anyString(), contains("This is a long enough chunk"));
    }

    @Test
    void sendMessageRAGSearchFailsGracefully() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "Hello", 20))
                .thenThrow(new RuntimeException("API error"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("d1")).thenReturn("");
        when(deepSeek.chat(anyString(), anyString())).thenReturn("Fallback answer");

        var result = chatService.sendMessage("c1", "Hello", "d1");
        assertEquals("Fallback answer", result);
    }

    @Test
    void sendMessageSavesBothMessages() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(deepSeek.chat(anyString(), anyString())).thenReturn("AI response");

        chatService.sendMessage("c1", "Hello", null);

        verify(mapper, times(2)).insertMessage(argThat(m -> {
            String role = m.getRole();
            return "user".equals(role) || "assistant".equals(role);
        }));
    }

    // === sendMessageStream tests ===

    @Test
    void sendMessageStreamConversationNotFoundThrows() {
        when(mapper.findById("c1")).thenReturn(null);
        Consumer<String> onToken = token -> {};

        assertThrows(RuntimeException.class,
                () -> chatService.sendMessageStream("c1", "Hello", null, onToken));
    }

    @Test
    void sendMessageStreamSavesUserMessageBeforeStreaming() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());

        AtomicBoolean userMsgSaved = new AtomicBoolean(false);
        doAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            if ("user".equals(msg.getRole())) userMsgSaved.set(true);
            return null;
        }).when(mapper).insertMessage(any(ChatMessage.class));

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("Hello");
            onToken.accept(" World");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "Hello", null, token -> {});

        assertTrue(userMsgSaved.get());
    }

    @Test
    void sendMessageStreamCallsOnTokenCallbacks() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());

        AtomicInteger tokenCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("Token1");
            onToken.accept("Token2");
            onToken.accept("Token3");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        Consumer<String> countingCallback = token -> tokenCount.incrementAndGet();
        chatService.sendMessageStream("c1", "Hello", null, countingCallback);

        assertEquals(3, tokenCount.get());
    }

    @Test
    void sendMessageStreamSavesAssistantMessageAfterStreaming() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("Hello");
            onToken.accept(" World");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "Hello", null, token -> {});

        verify(mapper, times(2)).insertMessage(any(ChatMessage.class));
    }

    @Test
    void sendMessageStreamWithDocumentUsesRAG() throws Exception {
        Conversation conv = new Conversation();
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "Hi", 20)).thenReturn(List.of("chunk"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("d1")).thenReturn("struct");

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("test");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "Hi", "d1", token -> {});

        verify(ragService).searchByDocument("d1", "Hi", 20);
        verify(ragService).extractStructure("d1");
    }
}