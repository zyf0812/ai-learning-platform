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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    private DocumentMapper docMapper;
    @Mock
    private RAGService ragService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatMapper, deepSeek, docMapper, ragService);
    }

    private Conversation conv(String id, String userId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId(userId);
        c.setTitle("测试对话");
        return c;
    }

    // ==================== 列表 & CRUD ====================

    @Test
    void listConversations_delegatesToMapper() {
        List<Conversation> expected = List.of(conv("c1", "u1"));
        when(chatMapper.findByUserId("u1")).thenReturn(expected);
        assertSame(expected, chatService.listConversations("u1"));
    }

    @Test
    void createConversation_setsDefaultsAndPersists() {
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        Conversation result = chatService.createConversation("u1", null);

        verify(chatMapper).insertConv(captor.capture());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals("新对话", captor.getValue().getTitle());
        assertEquals(8, captor.getValue().getId().length());
        // 返回值就是保存的对象
        assertEquals(captor.getValue().getId(), result.getId());
    }

    @Test
    void createConversation_customTitleUsed() {
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        chatService.createConversation("u1", "我的专属对话");
        verify(chatMapper).insertConv(captor.capture());
        assertEquals("我的专属对话", captor.getValue().getTitle());
    }

    @Test
    void deleteConversation_delegates() {
        chatService.deleteConversation("c1");
        verify(chatMapper).deleteConv("c1");
    }

    // ==================== sendMessage: 会话校验 ====================

    @Test
    void sendMessage_missingConvThrows() {
        when(chatMapper.findById("gone")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> chatService.sendMessage("gone", "你好", null));
        verify(chatMapper, never()).insertMessage(any());
    }

    @Test
    void sendMessageStream_missingConvThrowsBeforeAnySideEffect() {
        when(chatMapper.findById("gone")).thenReturn(null);
        assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("gone", "问题", null, t -> {}));
        // 用户消息绝不能被保存
        verify(chatMapper, never()).insertMessage(any());
    }

    // ==================== sendMessageStream: 用户消息先保存，AI 回答后保存（顺序正确性） ====================

    @Test
    void sendMessageStream_savesUserBeforeAICallThenSavesAI() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conv("c1", "u1"));
        when(chatMapper.findMessages("c1")).thenReturn(new ArrayList<>());

        // 流式回调: 捕获 DeepSeek.chatStream 调用的 token 顺序
        AtomicInteger deepSeekCalledAt = new AtomicInteger(-1);
        AtomicInteger userSavedAt = new AtomicInteger(-1);
        AtomicInteger aiSavedAt = new AtomicInteger(-1);
        int[] step = {0}; // clock

        doAnswer(inv -> {
            deepSeekCalledAt.set(++step[0]);
            java.util.function.Consumer<String> onToken = inv.getArgument(2);
            onToken.accept("token1");
            onToken.accept("token2");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        // 使用真实的 mapper 插入观察
        doAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            if ("user".equals(m.getRole())) userSavedAt.set(++step[0]);
            if ("assistant".equals(m.getRole())) aiSavedAt.set(++step[0]);
            return null;
        }).when(chatMapper).insertMessage(any());

        AtomicReference<String> lastToken = new AtomicReference<>();
        chatService.sendMessageStream("c1", "用户问题", null, lastToken::set);

        // 顺序: 用户消息保存(1) -> DeepSeek 调用(2) -> AI 回答保存(3)
        assertTrue(userSavedAt.get() > 0, "用户消息应已保存");
        assertTrue(deepSeekCalledAt.get() > 0, "DeepSeek 应已调用");
        assertTrue(aiSavedAt.get() > 0, "AI 回答应已保存");
        assertTrue(userSavedAt.get() < deepSeekCalledAt.get(),
            "用户消息必须在 DeepSeek 调用前保存（时间戳更早）");
        assertTrue(deepSeekCalledAt.get() < aiSavedAt.get(),
            "AI 回答必须在 DeepSeek 返回后保存（时间戳更晚）");

        // token 回调应该被正确推送给调用者
        assertEquals("token2", lastToken.get());

        // AI 回答的完整内容拼接
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        ChatMessage aiMsg = captor.getAllValues().stream()
            .filter(m -> "assistant".equals(m.getRole()))
            .findFirst().orElseThrow();
        assertEquals("token1token2", aiMsg.getContent());
    }

    // ==================== sendMessageStream: 事务传播存在性验证 ====================

    @Test
    void sendMessageStream_hasRequiresNewAnnotation() throws Exception {
        // 验证提交消息中的 REQUIRES_NEW 修复：方法必须标注正确的事务传播级别
        var method = ChatService.class.getMethod(
            "sendMessageStream",
            String.class, String.class, String.class, java.util.function.Consumer.class
        );
        var txAnno = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(txAnno,
            "sendMessageStream 必须标注 @Transactional（修复流式500错误的关键）");
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            txAnno.propagation(),
            "sendMessageStream 必须使用 Propagation.REQUIRES_NEW 以便在线程/异步中正确执行事务");
    }

    // ==================== sendMessage (同步版): 两条消息都被保存 ====================

    @Test
    void sendMessage_persistsBothUserAndAIMessage() throws Exception {
        when(chatMapper.findById("c1")).thenReturn(conv("c1", "u1"));
        when(chatMapper.findMessages("c1")).thenReturn(new ArrayList<>());
        when(deepSeek.chat(anyString(), anyString())).thenReturn("AI完整回答");

        chatService.sendMessage("c1", "用户提问", null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMapper, times(2)).insertMessage(captor.capture());
        List<ChatMessage> all = captor.getAllValues();

        ChatMessage userMsg = all.stream().filter(m -> "user".equals(m.getRole())).findFirst().orElseThrow();
        ChatMessage aiMsg = all.stream().filter(m -> "assistant".equals(m.getRole())).findFirst().orElseThrow();
        assertEquals("用户提问", userMsg.getContent());
        assertEquals("AI完整回答", aiMsg.getContent());
        // createdAt 不为空（防止忘记设置）
        assertNotNull(userMsg.getCreatedAt());
        assertNotNull(aiMsg.getCreatedAt());
        // 用户消息时间更早或相等（同一毫秒内也合理）
        assertTrue(
            !userMsg.getCreatedAt().isAfter(aiMsg.getCreatedAt()),
            "用户消息 createdAt 应早于或等于 AI 消息 createdAt"
        );
    }

    // ==================== 分页 & 计数 ====================

    @Test
    void countMessages_delegates() {
        when(chatMapper.countMessages("c1")).thenReturn(42);
        assertEquals(42, chatService.countMessages("c1"));
    }

    @Test
    void getMessagesPaged_delegatesWithParams() {
        List<ChatMessage> list = List.of(new ChatMessage());
        when(chatMapper.findMessagesPaged("c1", 10, 20)).thenReturn(list);
        assertSame(list, chatService.getMessagesPaged("c1", 10, 20));
    }
}
