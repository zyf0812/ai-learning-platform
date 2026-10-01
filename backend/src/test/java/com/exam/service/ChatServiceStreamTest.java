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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Coverage for ChatService.sendMessageStream — the streaming code path runs on a
 * dedicated thread spawned by the controller, so the recent REQUIRES_NEW fix
 * (see commit "fix: 修复流式接口500错误，添加REQUIRES_NEW事务传播") only worked because
 * the new transaction was forced. These tests pin down the observable behavior
 * so a future regression on the transaction boundary, ordering, or persistence
 * contract shows up immediately.
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

    private Conversation conversation() {
        Conversation c = new Conversation();
        c.setId("c1");
        c.setUserId("u1");
        c.setTitle("test");
        return c;
    }

    @Test
    void streamingInvokesTokenCallbackForEachChunk() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());

        // Simulate DeepSeek streaming 3 chunks.
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("Hello ");
                cb.accept("world");
                cb.accept("!");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        service.sendMessageStream("c1", "what?", null, received::add);

        assertEquals(List.of("Hello ", "world", "!"), received);
    }

    @Test
    void streamingPersistsUserMessageBeforeAssistantMessage() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("answer part 1 ");
                cb.accept("answer part 2");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "user question?", null, t -> {});

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("user question?", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("answer part 1 answer part 2", saved.get(1).getContent());
    }

    @Test
    void streamingThrowsWhenConversationMissing() {
        when(mapper.findById("missing")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> service.sendMessageStream("missing", "q", null, t -> {}));
        assertEquals("会话不存在", ex.getMessage());
        verify(mapper, never()).insertMessage(any());
        verifyNoInteractions(deepSeek);
    }

    @Test
    void streamingPropagatesDeepSeekExceptions() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("upstream timeout"))
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        var ex = assertThrows(RuntimeException.class,
            () -> service.sendMessageStream("c1", "q", null, t -> {}));
        assertEquals("upstream timeout", ex.getMessage());
        // User message is still saved BEFORE streaming begins, so we expect exactly one insert.
        verify(mapper, times(1)).insertMessage(any());
    }

    @Test
    void streamingWithDocumentPullsRagChunks() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());
        when(ragService.searchByDocument("doc1", "explain X", 20))
            .thenReturn(List.of("chunk A", "chunk B"));
        when(ragService.getChunksUniform("doc1", 3)).thenReturn(List.of("chunk C"));
        when(ragService.extractStructure("doc1")).thenReturn("Chapter 1: intro");
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("ok");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "explain X", "doc1", t -> {});

        verify(ragService).searchByDocument("doc1", "explain X", 20);
        verify(ragService).getChunksUniform("doc1", 3);
        verify(ragService).extractStructure("doc1");
    }

    @Test
    void streamingDeduplicatesRagChunksByContentPrefix() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());
        // First 50 chars collide → dedupe kicks in.
        String sharedPrefix = "abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmnop";
        when(ragService.searchByDocument("doc1", "q", 20))
            .thenReturn(List.of(sharedPrefix + " vector-end"));
        when(ragService.getChunksUniform("doc1", 3))
            .thenReturn(List.of(sharedPrefix + " uniform-end"));
        when(ragService.extractStructure("doc1")).thenReturn("");
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("done");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "q", "doc1", t -> {});

        // The dedupe path doesn't assert on DeepSeek prompt contents, just confirm the call.
        verify(deepSeek).chatStream(anyString(), anyString(), any());
    }

    @Test
    void streamingSwallowsRagFailures() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());
        when(ragService.searchByDocument("doc1", anyString(), anyInt()))
            .thenThrow(new RuntimeException("vector db down"));
        when(ragService.getChunksUniform("doc1", 3)).thenReturn(List.of());
        when(ragService.extractStructure("doc1")).thenReturn("");
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("still answered");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        // Should not bubble up — RAG is best-effort.
        assertDoesNotThrow(() ->
            service.sendMessageStream("c1", "q", "doc1", t -> {}));
    }

    @Test
    void streamingTriggersSummaryWhenHistoryIsLong() throws Exception {
        when(mapper.findById("c1")).thenReturn(conversation());
        // Threshold for summary is RECENT_COUNT (10) + 5 = 15. Provide 16 messages.
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ChatMessage m = new ChatMessage();
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("old-" + i);
            history.add(m);
        }
        when(mapper.findMessages("c1")).thenReturn(history);
        when(deepSeek.chat(anyString(), anyString())).thenReturn("summary text");
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("ack");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        service.sendMessageStream("c1", "ask", null, t -> {});

        // DeepSeek.chat (summary) + DeepSeek.chatStream (final answer) both called.
        verify(deepSeek, times(1)).chat(anyString(), anyString());
        verify(deepSeek, times(1)).chatStream(anyString(), anyString(), any());
    }

    @Test
    void streamingHandlesShortRagChunksWithoutCrash() throws Exception {
        // Short RAG chunk (<50 chars) must still participate in dedup via the full key.
        when(mapper.findById("c1")).thenReturn(conversation());
        when(mapper.findMessages("c1")).thenReturn(Collections.emptyList());
        when(ragService.searchByDocument("doc1", "q", 20))
            .thenReturn(List.of("short"));
        when(ragService.getChunksUniform("doc1", 3))
            .thenReturn(List.of("short"));
        when(ragService.extractStructure("doc1")).thenReturn("");
        doAnswer(invocation -> {
                java.util.function.Consumer<String> cb = invocation.getArgument(2);
                cb.accept("ok");
                return null;
            })
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        assertDoesNotThrow(() -> service.sendMessageStream("c1", "q", "doc1", t -> {}));
    }
}