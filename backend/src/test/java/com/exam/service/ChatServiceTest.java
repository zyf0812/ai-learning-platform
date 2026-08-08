package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.ChatMessage;
import com.exam.model.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatMapper chatMapper;

    @Mock
    private DeepSeekService deepSeek;

    @Mock
    private DocumentMapper docMapper;

    @Mock
    private RAGService ragService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatMapper, deepSeek, docMapper, ragService);
    }

    private Conversation createConversation(String id, String userId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId(userId);
        c.setTitle("Test Chat");
        return c;
    }

    @Nested
    @DisplayName("listConversations tests")
    class ListConversationsTests {

        @Test
        @DisplayName("returns conversations for user")
        void returnsConversations() {
            Conversation c = createConversation("conv-1", "user-1");
            when(chatMapper.findByUserId("user-1")).thenReturn(List.of(c));

            var result = chatService.listConversations("user-1");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("conv-1", result.get(0).getId());
        }
    }

    @Nested
    @DisplayName("createConversation tests")
    class CreateConversationTests {

        @Test
        @DisplayName("creates conversation with title")
        void createsConversationWithTitle() {
            Conversation result = chatService.createConversation("user-1", "My Chat");

            assertNotNull(result);
            assertEquals("user-1", result.getUserId());
            assertEquals("My Chat", result.getTitle());
            assertNotNull(result.getId());
            assertFalse(result.getId().isEmpty());

            verify(chatMapper).insertConv(any(Conversation.class));
        }

        @Test
        @DisplayName("uses default title when null provided")
        void usesDefaultTitle() {
            Conversation result = chatService.createConversation("user-1", null);

            assertEquals("新对话", result.getTitle());
        }

        @Test
        @DisplayName("generates unique IDs")
        void generatesUniqueIds() {
            Conversation c1 = chatService.createConversation("user-1", "Chat 1");
            Conversation c2 = chatService.createConversation("user-1", "Chat 2");

            assertNotEquals(c1.getId(), c2.getId());
        }
    }

    @Nested
    @DisplayName("getConversation tests")
    class GetConversationTests {

        @Test
        @DisplayName("returns conversation when found")
        void returnsConversation() {
            Conversation c = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(c);

            var result = chatService.getConversation("conv-1");
            assertNotNull(result);
            assertEquals("conv-1", result.getId());
        }

        @Test
        @DisplayName("returns null when not found")
        void returnsNull() {
            when(chatMapper.findById("unknown")).thenReturn(null);

            var result = chatService.getConversation("unknown");
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("getMessages tests")
    class GetMessagesTests {

        @Test
        @DisplayName("returns messages for conversation")
        void returnsMessages() {
            ChatMessage msg = new ChatMessage();
            msg.setId("msg-1");
            msg.setRole("user");
            msg.setContent("Hello");
            when(chatMapper.findMessages("conv-1")).thenReturn(List.of(msg));

            var result = chatService.getMessages("conv-1");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("paged messages work correctly")
        void pagedMessages() {
            when(chatMapper.findMessagesPaged("conv-1", 20, 0)).thenReturn(new ArrayList<>());
            when(chatMapper.countMessages("conv-1")).thenReturn(0);

            var messages = chatService.getMessagesPaged("conv-1", 20, 0);
            int count = chatService.countMessages("conv-1");

            assertNotNull(messages);
            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("sendMessage tests")
    class SendMessageTests {

        @Test
        @DisplayName("throws when conversation does not exist")
        void throwsWhenConversationNotFound() {
            when(chatMapper.findById("unknown")).thenReturn(null);

            assertThrows(RuntimeException.class,
                () -> chatService.sendMessage("unknown", "Hello", null));
        }

        @Test
        @DisplayName("sends message and returns answer")
        void sendsMessageAndReturnsAnswer() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());
            when(deepSeek.chat(anyString(), anyString())).thenReturn("AI answer");

            String result = chatService.sendMessage("conv-1", "Hello", null);

            assertEquals("AI answer", result);

            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(chatMapper, times(2)).insertMessage(captor.capture());

            List<ChatMessage> inserted = captor.getAllValues();
            assertEquals("user", inserted.get(0).getRole());
            assertEquals("Hello", inserted.get(0).getContent());
            assertEquals("assistant", inserted.get(1).getRole());
            assertEquals("AI answer", inserted.get(1).getContent());
        }

        @Test
        @DisplayName("RAG search is triggered when documentId is provided")
        void ragSearchTriggered() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());
            when(ragService.searchByDocument("doc-1", "Hello", 20)).thenReturn(List.of("chunk1"));
            when(ragService.getChunksUniform("doc-1", 3)).thenReturn(new ArrayList<>());
            when(deepSeek.chat(anyString(), anyString())).thenReturn("AI answer");

            chatService.sendMessage("conv-1", "Hello", "doc-1");

            verify(ragService).searchByDocument(eq("doc-1"), eq("Hello"), eq(20));
            verify(ragService).getChunksUniform(eq("doc-1"), eq(3));
            verify(ragService).extractStructure("doc-1");
        }

        @Test
        @DisplayName("RAG failure is handled gracefully")
        void ragFailureHandled() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());
            when(ragService.searchByDocument(eq("doc-1"), anyString(), eq(20)))
                .thenThrow(new RuntimeException("RAG service down"));
            when(ragService.getChunksUniform("doc-1", 3)).thenReturn(new ArrayList<>());
            when(deepSeek.chat(anyString(), anyString())).thenReturn("AI answer without RAG");

            String result = chatService.sendMessage("conv-1", "Hello", "doc-1");

            assertEquals("AI answer without RAG", result);
        }

        @Test
        @DisplayName("deduplication of RAG chunks works correctly")
        void ragChunkDeduplication() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());
            when(ragService.searchByDocument("doc-1", "q", 20))
                .thenReturn(List.of("重复内容超过50字符的chunk用于测试去重逻辑"));
            when(ragService.getChunksUniform("doc-1", 3))
                .thenReturn(List.of("重复内容超过50字符的chunk用于测试去重逻辑-额外"));
            when(deepSeek.chat(anyString(), anyString())).thenReturn("answer");

            String result = chatService.sendMessage("conv-1", "q", "doc-1");
            assertEquals("answer", result);
        }

        @Test
        @DisplayName("context includes document structure when available")
        void contextIncludesDocStructure() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());
            when(ragService.searchByDocument("doc-1", "q", 20)).thenReturn(new ArrayList<>());
            when(ragService.getChunksUniform("doc-1", 3)).thenReturn(new ArrayList<>());
            when(ragService.extractStructure("doc-1")).thenReturn("【文档开头】\n这是文档内容");
            when(deepSeek.chat(anyString(), anyString())).thenReturn("answer");

            String result = chatService.sendMessage("conv-1", "q", "doc-1");

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(deepSeek).chat(anyString(), promptCaptor.capture());
            assertTrue(promptCaptor.getValue().contains("【文档结构概览】"));
        }
    }

    @Nested
    @DisplayName("sendMessageStream tests")
    class SendMessageStreamTests {

        @Test
        @DisplayName("throws when conversation does not exist")
        void throwsWhenConversationNotFound() {
            when(chatMapper.findById("unknown")).thenReturn(null);

            Consumer<String> noop = token -> {};
            assertThrows(RuntimeException.class,
                () -> chatService.sendMessageStream("unknown", "Hello", null, noop));
        }

        @Test
        @DisplayName("streams tokens and saves messages")
        void streamsAndSavesMessages() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());

            List<String> tokens = List.of("Hello", " ", "World", "!");
            AtomicReference<String> receivedTokens = new AtomicReference<>("");
            doAnswer(invocation -> {
                Consumer<String> callback = invocation.getArgument(2);
                for (String token : tokens) {
                    callback.accept(token);
                }
                return null;
            }).when(deepSeek).chatStream(anyString(), anyString(), any());

            chatService.sendMessageStream("conv-1", "Hello", null, token -> {
                receivedTokens.set(receivedTokens.get() + token);
            });

            assertEquals("Hello World!", receivedTokens.get());

            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(chatMapper, times(2)).insertMessage(captor.capture());

            List<ChatMessage> inserted = captor.getAllValues();
            assertEquals("user", inserted.get(0).getRole());
            assertEquals("Hello", inserted.get(0).getContent());
            assertEquals("assistant", inserted.get(1).getRole());
            assertEquals("Hello World!", inserted.get(1).getContent());
        }

        @Test
        @DisplayName("stream with RAG document triggers RAG search")
        void streamWithRAGDoc() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());
            when(ragService.searchByDocument("doc-1", "q", 20)).thenReturn(List.of("chunk1"));
            when(ragService.getChunksUniform("doc-1", 3)).thenReturn(new ArrayList<>());

            doAnswer(invocation -> {
                Consumer<String> callback = invocation.getArgument(2);
                callback.accept("answer");
                return null;
            }).when(deepSeek).chatStream(anyString(), anyString(), any());

            chatService.sendMessageStream("conv-1", "q", "doc-1", token -> {});

            verify(ragService).searchByDocument(eq("doc-1"), eq("q"), eq(20));
        }

        @Test
        @DisplayName("stream saves user message before AI response")
        void streamUserMessageSavedBeforeAI() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);
            when(chatMapper.findMessages("conv-1")).thenReturn(new ArrayList<>());

            final List<ChatMessage> savedOrder = new ArrayList<>();
            doAnswer(invocation -> {
                ChatMessage msg = invocation.getArgument(0);
                savedOrder.add(msg);
                return null;
            }).when(chatMapper).insertMessage(any(ChatMessage.class));

            doAnswer(invocation -> {
                Consumer<String> callback = invocation.getArgument(2);
                callback.accept("AI");
                return null;
            }).when(deepSeek).chatStream(anyString(), anyString(), any());

            chatService.sendMessageStream("conv-1", "Hello", null, token -> {});

            assertEquals(2, savedOrder.size());
            assertEquals("user", savedOrder.get(0).getRole());
            assertEquals("assistant", savedOrder.get(1).getRole());
        }
    }

    @Nested
    @DisplayName("deleteConversation tests")
    class DeleteConversationTests {

        @Test
        @DisplayName("deletes conversation")
        void deletesConversation() {
            assertDoesNotThrow(() -> chatService.deleteConversation("conv-1"));
            verify(chatMapper).deleteConv("conv-1");
        }
    }

    @Nested
    @DisplayName("sendMessage with history summary tests")
    class HistorySummaryTests {

        @Test
        @DisplayName("summarizes old messages when history exceeds threshold")
        void summarizesWhenHistoryExceedsThreshold() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);

            List<ChatMessage> longHistory = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ChatMessage m = new ChatMessage();
                m.setId("msg-" + i);
                m.setRole(i % 2 == 0 ? "user" : "assistant");
                m.setContent("Message " + i);
                longHistory.add(m);
            }
            when(chatMapper.findMessages("conv-1")).thenReturn(longHistory);
            when(deepSeek.chat(contains("压缩"), anyString())).thenReturn("历史摘要");
            when(deepSeek.chat(anyString(), anyString())).thenReturn("AI answer");

            String result = chatService.sendMessage("conv-1", "New question", null);
            assertEquals("AI answer", result);

            verify(deepSeek, times(2)).chat(anyString(), anyString());
        }

        @Test
        @DisplayName("does not summarize when history is below threshold")
        void doesNotSummarizeBelowThreshold() throws Exception {
            Conversation conv = createConversation("conv-1", "user-1");
            when(chatMapper.findById("conv-1")).thenReturn(conv);

            List<ChatMessage> shortHistory = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ChatMessage m = new ChatMessage();
                m.setRole("user");
                m.setContent("Msg " + i);
                shortHistory.add(m);
            }
            when(chatMapper.findMessages("conv-1")).thenReturn(shortHistory);
            when(deepSeek.chat(anyString(), anyString())).thenReturn("answer");

            chatService.sendMessage("conv-1", "q", null);

            verify(deepSeek, times(1)).chat(anyString(), anyString());
        }
    }
}