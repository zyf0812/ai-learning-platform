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

    @Test
    void sendMessageStreamPersistsUserThenAssistantAndForwardsTokens() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(List.of());
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("你好");
            onToken.accept("世界");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        chatService.sendMessageStream("c1", "问题？", null, received::add);

        assertEquals(List.of("你好", "世界"), received);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        ChatMessage userMessage = captor.getAllValues().get(0);
        ChatMessage assistantMessage = captor.getAllValues().get(1);
        assertEquals("user", userMessage.getRole());
        assertEquals("问题？", userMessage.getContent());
        assertEquals("assistant", assistantMessage.getRole());
        assertEquals("你好世界", assistantMessage.getContent());
        assertFalse(assistantMessage.getCreatedAt().isBefore(userMessage.getCreatedAt()),
            "assistant message must not be timestamped before the user message");
        verifyNoInteractions(ragService);
    }

    @Test
    void sendMessageStreamThrowsWhenConversationMissing() {
        when(chatMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("missing", "q", null, token -> {}));

        assertEquals("会话不存在", ex.getMessage());
        verify(chatMapper, never()).insertMessage(any());
        verifyNoInteractions(deepSeek);
    }

    @Test
    void sendMessageStreamDeduplicatesRagChunksPreferringVectorResults() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "q", 20))
            .thenReturn(new ArrayList<>(List.of("重复片段", "向量片段")));
        when(ragService.getChunksUniform("d1", 3))
            .thenReturn(new ArrayList<>(List.of("重复片段", "均匀片段")));
        when(ragService.extractStructure("d1")).thenReturn("");
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("answer");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "q", "d1", token -> {});

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), userPrompt.capture(), any());
        String prompt = userPrompt.getValue();
        assertTrue(prompt.contains("【相关知识】"));
        assertEquals(1, occurrences(prompt, "重复片段"), "duplicate chunk must appear once");
        assertTrue(prompt.contains("向量片段"));
        assertTrue(prompt.contains("均匀片段"));
    }

    @Test
    void sendMessageStreamKeepsGoingWhenRagSearchFails() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(List.of());
        when(ragService.searchByDocument("d1", "q", 20)).thenThrow(new RuntimeException("embedding down"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(new ArrayList<>(List.of("均匀片段")));
        when(ragService.extractStructure("d1")).thenReturn("");
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("answer");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "q", "d1", token -> {});

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), userPrompt.capture(), any());
        assertTrue(userPrompt.getValue().contains("均匀片段"),
            "uniform chunks must still be used when vector search fails");
        verify(chatMapper).insertMessage(argThat(m -> "assistant".equals(m.getRole())));
    }

    @Test
    void sendMessageStreamSummarizesLongHistoryIntoContext() throws Exception {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ChatMessage message = new ChatMessage();
            message.setRole(i % 2 == 0 ? "user" : "assistant");
            message.setContent("历史消息" + i);
            history.add(message);
        }
        when(chatMapper.findById("c1")).thenReturn(conversation("c1"));
        when(chatMapper.findMessages("c1")).thenReturn(history);
        when(deepSeek.chat(anyString(), anyString())).thenReturn("历史摘要");
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("answer");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "q", null, token -> {});

        ArgumentCaptor<String> summaryInput = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chat(anyString(), summaryInput.capture());
        assertTrue(summaryInput.getValue().contains("历史消息15"),
            "oldest message should be part of the summarized history");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), userPrompt.capture(), any());
        assertTrue(userPrompt.getValue().contains("【对话背景】"));
        assertTrue(userPrompt.getValue().contains("历史摘要"));
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        return conversation;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
