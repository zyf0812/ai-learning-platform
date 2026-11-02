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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 最近的修复点：流式接口 500 错误，新增 REQUIRES_NEW 事务并调整消息落库顺序。
 * 这些测试锁定流式路径的消息顺序、异常行为，以及 RAG/摘要失败时的降级能力。
 */
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

    private Conversation conversation() {
        Conversation c = new Conversation();
        c.setId("c1");
        c.setUserId("u1");
        return c;
    }

    private ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void sendMessageStreamEmitsTokensAndPersistsUserBeforeAssistant() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation());

        List<String> tokens = new ArrayList<>();
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("你");
            onToken.accept("好");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "hi", null, tokens::add);

        assertEquals(List.of("你", "好"), tokens);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("hi", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("你好", saved.get(1).getContent());

        // 修复的核心：用户消息必须在流式调用之前落库，否则排序会错乱
        InOrder inOrder = inOrder(chatMapper, deepSeek);
        inOrder.verify(chatMapper).insertMessage(any(ChatMessage.class));
        inOrder.verify(deepSeek).chatStream(anyString(), anyString(), any());
        inOrder.verify(chatMapper).insertMessage(any(ChatMessage.class));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getValue().contains("用户: hi"));
    }

    @Test
    void sendMessageStreamRejectsMissingConversationBeforeCallingModel() {
        List<String> tokens = new ArrayList<>();

        assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("missing", "hi", null, tokens::add));

        verifyNoInteractions(deepSeek);
        verify(chatMapper, never()).insertMessage(any(ChatMessage.class));
        assertTrue(tokens.isEmpty());
    }

    @Test
    void sendMessageStreamSurvivesRagSearchFailureAndUsesFallbackChunks() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation());
        when(ragService.searchByDocument("d1", "question", 20))
            .thenThrow(new RuntimeException("vector database down"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of("uniform-chunk"));
        when(ragService.extractStructure("d1")).thenReturn("structure-X");

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("ok");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "question", "d1", token -> { });

        verify(chatMapper, times(2)).insertMessage(any(ChatMessage.class));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getValue().contains("【文档结构概览】"));
        assertTrue(promptCaptor.getValue().contains("structure-X"));
        assertTrue(promptCaptor.getValue().contains("uniform-chunk"));
    }

    @Test
    void sendMessageStreamSummarizesHistoryBeyondWindow() throws Exception {
        Conversation conv = conversation();
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(message("user", "msg-" + (19 - i)));
        }

        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(history);
        when(deepSeek.chat(anyString(), anyString())).thenReturn("历史摘要");

        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("答");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "new question", null, token -> { });

        ArgumentCaptor<String> summaryPrompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chat(anyString(), summaryPrompt.capture());
        assertTrue(summaryPrompt.getValue().contains("msg-0"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeek).chatStream(anyString(), promptCaptor.capture(), any());
        assertTrue(promptCaptor.getValue().contains("【对话背景】"));
        assertTrue(promptCaptor.getValue().contains("历史摘要"));
    }

    @Test
    void streamingFailureStillKeepsUserMessageButNotAssistantMessage() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation());
        doThrow(new IOException("stream down"))
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        assertThrows(IOException.class,
            () -> chatService.sendMessageStream("c1", "hi", null, token -> { }));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(1)).insertMessage(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("hi", captor.getValue().getContent());
    }

    @Test
    void sendMessagePersistsQuestionAndAnswerInOrder() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conversation());
        when(deepSeek.chat(anyString(), anyString())).thenReturn("answer");

        String answer = chatService.sendMessage("c1", "hello", null);

        assertEquals("answer", answer);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("hello", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("answer", saved.get(1).getContent());
    }

    @Test
    void createConversationUsesDefaultTitle() {
        Conversation created = chatService.createConversation("u1", null);

        assertEquals("新对话", created.getTitle());
        assertEquals("u1", created.getUserId());
        verify(chatMapper).insertConv(created);
    }
}
