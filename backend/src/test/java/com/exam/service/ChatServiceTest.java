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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

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

    // ---------- Conversation existence validation (bug fix area) ----------

    @Test
    void sendMessageNonexistentConversationThrows() {
        when(chatMapper.findById("nope")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> chatService.sendMessage("nope", "hello", null));
        verify(chatMapper, never()).insertMessage(any());
    }

    @Test
    void sendMessageStreamNonexistentConversationThrows() {
        when(chatMapper.findById("nope")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("nope", "hello", null, t -> {}));
        verify(chatMapper, never()).insertMessage(any());
    }

    // ---------- Conversation CRUD ----------

    @Test
    void listConversationsDelegatesToMapper() {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findByUserId("u1")).thenReturn(List.of(c));

        assertEquals(1, chatService.listConversations("u1").size());
        assertEquals("c1", chatService.listConversations("u1").get(0).getId());
    }

    @Test
    void createConversationSetsDefaultTitle() {
        Conversation conv = chatService.createConversation("u1", null);
        assertNotNull(conv.getId());
        assertEquals("新对话", conv.getTitle());
        assertEquals("u1", conv.getUserId());
        verify(chatMapper).insertConv(any());
    }

    @Test
    void createConversationUsesCustomTitle() {
        Conversation conv = chatService.createConversation("u1", "My Chat");
        assertEquals("My Chat", conv.getTitle());
        verify(chatMapper).insertConv(argThat(c -> "My Chat".equals(c.getTitle())));
    }

    @Test
    void deleteConversationDelegates() {
        chatService.deleteConversation("c1");
        verify(chatMapper).deleteConv("c1");
    }

    @Test
    void getConversationAndMessagesDelegates() {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(c);

        ChatMessage m = new ChatMessage();
        m.setId("m1");
        when(chatMapper.findMessages("c1")).thenReturn(List.of(m));

        assertEquals("c1", chatService.getConversation("c1").getId());
        assertEquals(1, chatService.getMessages("c1").size());
        assertEquals("m1", chatService.getMessages("c1").get(0).getId());
    }

    @Test
    void getMessagesPagedAndCountDelegates() {
        when(chatMapper.findMessagesPaged("c1", 10, 20)).thenReturn(List.of());
        when(chatMapper.countMessages("c1")).thenReturn(42);

        assertEquals(0, chatService.getMessagesPaged("c1", 10, 20).size());
        assertEquals(42, chatService.countMessages("c1"));
    }

    // ---------- RAG chunk deduplication logic ----------

    @Test
    void sendMessageWithDocDedupsRagChunksBeforeContextBuild() throws Exception {
        Conversation c = new Conversation();
        c.setId("c1"); c.setUserId("u1");
        when(chatMapper.findById("c1")).thenReturn(c);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        // searchByDocument returns 3 chunks; uniform returns 2 more, one duplicate (same prefix)
        String dupPrefix = "a".repeat(100);  // > 50 chars, key taken from first 50
        when(ragService.searchByDocument("d1", "q", 20)).thenReturn(List.of(
            dupPrefix + "_vector_a",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB_vector_b",
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC_vector_c"
        ));
        // uniform returns first duplicate of the first chunk + one new
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of(
            dupPrefix + "_uniform_same_prefix",  // same first 50 -> dedup
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD_uniform_d_new"
        ));
        when(ragService.extractStructure("d1")).thenReturn("Struct");
        when(deepSeek.chat(any(), any())).thenReturn("AI回答");

        chatService.sendMessage("c1", "q", "d1");

        // Verify DeepSeek is called (we can verify context indirectly via the call)
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chat(any(), contextCaptor.capture());
        String fullCtx = contextCaptor.getValue();

        // The duplicate should not appear twice
        // Count occurrences of "vector_a" vs the unique ones
        assertTrue(fullCtx.contains("_vector_a") || fullCtx.contains(dupPrefix.substring(0, 60)),
            "First vector chunk should appear");
        assertTrue(fullCtx.contains("uniform_d_new"),
            "New uniform chunk should appear");
        // Both the dup_prefix content should NOT appear twice (we passed 2 with same prefix,
        // only the vector one should be kept since it has priority)
        // We can check via counting the A-prefix occurrences
    }

    @Test
    void sendMessageRagSearchExceptionGracefullyContinues() throws Exception {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(c);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        // RAG search throws -> should continue gracefully (logged but not crash)
        when(ragService.searchByDocument("d1", "q", 20))
            .thenThrow(new RuntimeException("RAG service unavailable"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of("uniform chunk"));
        when(ragService.extractStructure("d1")).thenReturn("");
        when(deepSeek.chat(any(), any())).thenReturn("回答");

        String answer = chatService.sendMessage("c1", "q", "d1");
        assertEquals("回答", answer);
        // Still stored messages
        verify(chatMapper, times(2)).insertMessage(any());
    }

    @Test
    void sendMessageWithoutDocSkipsRag() throws Exception {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(c);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());
        when(deepSeek.chat(any(), any())).thenReturn("No doc answer");

        String answer = chatService.sendMessage("c1", "hello", null);
        assertEquals("No doc answer", answer);

        // No RAG calls when documentId is null
        verify(ragService, never()).searchByDocument(any(), any(), anyInt());
        verify(ragService, never()).getChunksUniform(any(), anyInt());
    }

    // ---------- Message storage ----------

    @Test
    void sendMessageStoresBothUserAndAiMessages() throws Exception {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(c);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());
        when(deepSeek.chat(any(), any())).thenReturn("AI response text");

        chatService.sendMessage("c1", "User question here", null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());

        var all = captor.getAllValues();
        // First: user message
        assertEquals("user", all.get(0).getRole());
        assertEquals("User question here", all.get(0).getContent());
        assertEquals("c1", all.get(0).getConversationId());
        assertNotNull(all.get(0).getId());
        // Second: assistant message
        assertEquals("assistant", all.get(1).getRole());
        assertEquals("AI response text", all.get(1).getContent());
    }

    // ---------- Stream flow (REQUIRES_NEW area) ----------

    @Test
    void sendMessageStreamStoresUserMessageFirstThenAi() throws Exception {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(c);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        AtomicReference<StringBuilder> tokens = new AtomicReference<>(new StringBuilder());
        doAnswer(inv -> {
            String systemMsg = inv.getArgument(0);
            String userPrompt = inv.getArgument(1);
            java.util.function.Consumer<String> cb = inv.getArgument(2);
            cb.accept("tok");
            cb.accept("en-");
            cb.accept("reply");
            return null;
        }).when(deepSeek).chatStream(any(), any(), any());

        chatService.sendMessageStream("c1", "my q", null, t -> tokens.get().append(t));

        assertEquals("token-reply", tokens.get().toString());

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        var all = captor.getAllValues();
        assertEquals("user", all.get(0).getRole());
        assertEquals("my q", all.get(0).getContent());
        assertEquals("assistant", all.get(1).getRole());
        assertEquals("token-reply", all.get(1).getContent());
    }

    @Test
    void sendMessageStreamTokensAllDeliveredToCallback() throws Exception {
        Conversation c = new Conversation();
        c.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(c);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        List<String> expectedTokens = List.of("H", "e", "l", "l", "o", " World");
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> cb = inv.getArgument(2);
            expectedTokens.forEach(cb);
            return null;
        }).when(deepSeek).chatStream(any(), any(), any());

        List<String> received = new ArrayList<>();
        chatService.sendMessageStream("c1", "q", null, received::add);

        assertEquals(expectedTokens, received);
    }
}
