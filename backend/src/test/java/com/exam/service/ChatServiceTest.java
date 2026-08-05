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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ========== 会话列表 ==========

    @Test
    void listConversationsReturnsFromMapper() {
        Conversation c1 = new Conversation(); c1.setId("c1"); c1.setTitle("对话1");
        Conversation c2 = new Conversation(); c2.setId("c2"); c2.setTitle("对话2");
        List<Conversation> list = List.of(c1, c2);

        when(chatMapper.findByUserId("u1")).thenReturn(list);

        var result = chatService.listConversations("u1");

        assertEquals(list, result);
        verify(chatMapper).findByUserId("u1");
    }

    // ========== 创建会话 ==========

    @Test
    void createConversationWithCustomTitle() {
        doNothing().when(chatMapper).insertConv(any());

        var result = chatService.createConversation("u1", "我的学习对话");

        assertNotNull(result.getId());
        assertEquals("我的学习对话", result.getTitle());
        assertEquals("u1", result.getUserId());
        verify(chatMapper).insertConv(argThat(c ->
            c.getTitle().equals("我的学习对话") && "u1".equals(c.getUserId())
        ));
    }

    @Test
    void createConversationNullTitleDefaultsToNewConversation() {
        doNothing().when(chatMapper).insertConv(any());

        var result = chatService.createConversation("u1", null);

        assertEquals("新对话", result.getTitle());
    }

    @Test
    void createConversationEmptyTitleDefaults() {
        doNothing().when(chatMapper).insertConv(any());

        var result = chatService.createConversation("u1", "");

        assertEquals("新对话", result.getTitle());
    }

    // ========== 获取会话 ==========

    @Test
    void getConversationReturnsFromMapper() {
        Conversation c = new Conversation();
        c.setId("c1");
        c.setTitle("对话1");

        when(chatMapper.findById("c1")).thenReturn(c);

        var result = chatService.getConversation("c1");

        assertEquals("c1", result.getId());
        assertEquals("对话1", result.getTitle());
    }

    // ========== 获取消息 ==========

    @Test
    void getMessagesReturnsFromMapper() {
        ChatMessage m1 = new ChatMessage(); m1.setId("m1"); m1.setRole("user");
        ChatMessage m2 = new ChatMessage(); m2.setId("m2"); m2.setRole("assistant");
        List<ChatMessage> msgs = List.of(m1, m2);

        when(chatMapper.findMessages("c1")).thenReturn(msgs);

        var result = chatService.getMessages("c1");

        assertEquals(msgs, result);
    }

    @Test
    void getMessagesPagedReturnsLimited() {
        when(chatMapper.findMessagesPaged("c1", 20, 40))
            .thenReturn(List.of());

        var result = chatService.getMessagesPaged("c1", 20, 40);

        assertNotNull(result);
        verify(chatMapper).findMessagesPaged("c1", 20, 40);
    }

    @Test
    void countMessagesReturnsNumber() {
        when(chatMapper.countMessages("c1")).thenReturn(50);

        int count = chatService.countMessages("c1");

        assertEquals(50, count);
    }

    // ========== 删除会话 ==========

    @Test
    void deleteConversationCallsMapper() {
        doNothing().when(chatMapper).deleteConv("c1");

        chatService.deleteConversation("c1");

        verify(chatMapper).deleteConv("c1");
    }

    // ========== sendMessage：会话不存在 ==========

    @Test
    void sendMessageToNonExistentConversationThrows() {
        when(chatMapper.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> chatService.sendMessage("nonexistent", "你好", null));
    }

    // ========== sendMessage：基本流程（无RAG、无摘要） ==========

    @Test
    void sendMessageSimpleFlowReturnsAnswerAndSavesMessages() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        conv.setUserId("u1");

        // 历史消息少于摘要阈值，不触发摘要
        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());
        when(deepSeek.chat(anyString(), anyString())).thenReturn("这是AI的回答");
        doNothing().when(chatMapper).insertMessage(any());

        String answer = chatService.sendMessage("c1", "你好，请介绍Java", null);

        assertEquals("这是AI的回答", answer);

        // 验证用户消息和AI回答都被保存
        verify(chatMapper, times(2)).insertMessage(any());
        verify(chatMapper).insertMessage(argThat(m ->
            "user".equals(m.getRole()) && "你好，请介绍Java".equals(m.getContent())
        ));
        verify(chatMapper).insertMessage(argThat(m ->
            "assistant".equals(m.getRole()) && "这是AI的回答".equals(m.getContent())
        ));
    }

    // ========== sendMessage：带文档引用（RAG）==========

    @Test
    void sendMessageWithDocumentCallsRAGSearch() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        // RAG 返回一些分片
        List<String> chunks = List.of(
            "Java是一种面向对象编程语言",
            "Java具有跨平台特性"
        );
        when(ragService.searchByDocument("d1", "什么是Java", 20)).thenReturn(chunks);
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of(
            "Spring是Java框架", // 与搜索结果不重复
            "JVM是Java虚拟机"
        ));
        when(ragService.extractStructure("d1")).thenReturn("文档结构\n1. Java基础");

        when(deepSeek.chat(anyString(), contains("文档结构概览")))
            .thenReturn("基于文档的回答");
        doNothing().when(chatMapper).insertMessage(any());

        String answer = chatService.sendMessage("c1", "什么是Java", "d1");

        assertEquals("基于文档的回答", answer);
        verify(ragService).searchByDocument("d1", "什么是Java", 20);
        verify(ragService).getChunksUniform("d1", 3);
        verify(ragService).extractStructure("d1");
    }

    @Test
    void sendMessageRAGSearchFailureDoesNotCrash() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        // RAG 搜索抛异常，但被 catch，不影响主流程
        when(ragService.searchByDocument("d1", "问题", 20))
            .thenThrow(new RuntimeException("RAG unavailable"));
        when(ragService.getChunksUniform("d1", 3)).thenReturn(List.of("备用分片"));
        when(ragService.extractStructure("d1")).thenReturn("骨架");

        when(deepSeek.chat(anyString(), anyString())).thenReturn("AI回答");
        doNothing().when(chatMapper).insertMessage(any());

        // 不应抛出异常
        assertDoesNotThrow(() -> chatService.sendMessage("c1", "问题", "d1"));
    }

    // ========== sendMessageStream：基本流程（REQUIRES_NEW 修复点）==========

    @Test
    void sendMessageStreamSavesBothMessagesAndCallsTokenCallback() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        // 模拟流式输出：3个token
        String[] tokens = { "你", "好", "！" };
        doAnswer(inv -> {
            java.util.function.Consumer<String> callback = inv.getArgument(2);
            for (String t : tokens) callback.accept(t);
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        doNothing().when(chatMapper).insertMessage(any());

        StringBuilder receivedTokens = new StringBuilder();
        chatService.sendMessageStream("c1", "你好", null, token -> {
            receivedTokens.append(token);
        });

        // 验证所有 token 都回调了
        assertEquals("你好！", receivedTokens.toString());

        // 验证用户消息和完整AI回答都被保存
        verify(chatMapper, times(2)).insertMessage(any());
        verify(chatMapper).insertMessage(argThat(m ->
            "user".equals(m.getRole()) && "你好".equals(m.getContent())
        ));
        verify(chatMapper).insertMessage(argThat(m ->
            "assistant".equals(m.getRole()) && "你好！".equals(m.getContent())
        ));
    }

    @Test
    void sendMessageStreamConversationNotFoundThrows() {
        when(chatMapper.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("nonexistent", "你好", null, token -> {}));
    }

    @Test
    void sendMessageStreamUserMessageSavedBeforeStream() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        AtomicInteger messageCount = new AtomicInteger(0);
        List<String> savedRoles = new ArrayList<>();

        // 记录 insertMessage 调用顺序
        doAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            savedRoles.add(m.getRole());
            messageCount.incrementAndGet();
            return null;
        }).when(chatMapper).insertMessage(any());

        doAnswer(inv -> {
            java.util.function.Consumer<String> callback = inv.getArgument(2);
            // 流式输出期间，用户消息应该已经保存
            assertEquals(1, messageCount.get()); // 此时用户消息已保存
            assertEquals("user", savedRoles.get(0));
            callback.accept("a");
            callback.accept("b");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        chatService.sendMessageStream("c1", "Hello", null, token -> {});

        // 最终共2条消息，顺序：user -> assistant
        assertEquals(2, messageCount.get());
        assertEquals("user", savedRoles.get(0));
        assertEquals("assistant", savedRoles.get(1));
    }

    @Test
    void sendMessageStreamDeepSeekExceptionPropagates() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        when(chatMapper.findById("c1")).thenReturn(conv);
        when(chatMapper.findMessages("c1")).thenReturn(List.of());

        doThrow(new RuntimeException("DeepSeek API error"))
            .when(deepSeek).chatStream(anyString(), anyString(), any());

        // 用户消息应在 stream 前就插入了，即使 stream 失败也会存在
        doNothing().when(chatMapper).insertMessage(any());

        var ex = assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("c1", "你好", null, token -> {}));
        assertEquals("DeepSeek API error", ex.getMessage());
    }
}
