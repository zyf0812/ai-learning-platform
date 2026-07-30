package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.ChatMessage;
import com.exam.model.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 覆盖 ChatService 的流式发送与消息持久化行为。
 * 重点：sendMessageStream 是最近 bug 修复的目标（添加 REQUIRES_NEW 事务传播），
 * 此前没有任何测试。这里验证其关键回归行为：
 *  - 会话不存在时抛出
 *  - 用户消息在流式调用前持久化（保证时间戳顺序与崩溃安全）
 *  - 流式回调逐 token 转发，完整回答在流结束后持久化
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatMapper mapper;

    @Mock
    private DeepSeekService deepSeek;

    @Mock
    private DocumentMapper docMapper;

    @Mock
    private RAGService ragService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(mapper, deepSeek, docMapper, ragService);
    }

    @Test
    void sendMessageStreamThrowsWhenConversationNotFound() {
        when(mapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> chatService.sendMessageStream("missing", "q", null, s -> {}));
        assertEquals("会话不存在", ex.getMessage());
        verify(mapper, never()).insertMessage(any());
        verifyNoInteractions(deepSeek);
    }

    /**
     * 回归测试：流式发送时用户消息必须在调用 DeepSeek 之前落库，
     * AI 完整回答在流结束后落库。这保证：
     *  1. 用户消息时间戳早于 AI 回答（排序正确，修复前顺序颠倒导致 500）
     *  2. 即使流式中途失败，用户消息已持久化
     *  3. onToken 回调按到达顺序逐个转发给调用方
     */
    @Test
    void sendMessageStreamPersistsUserMessageBeforeStreamingAndFullAnswerAfter() throws Exception {
        Conversation conv = new Conversation();
        conv.setId("c1");
        conv.setUserId("u1");
        when(mapper.findById("c1")).thenReturn(conv);
        when(mapper.findMessages("c1")).thenReturn(List.of());

        // 模拟 DeepSeek 流式回调：逐 token 推送
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(2);
            onToken.accept("Hello");
            onToken.accept(" ");
            onToken.accept("World");
            return null;
        }).when(deepSeek).chatStream(anyString(), anyString(), any());

        List<String> received = new ArrayList<>();
        chatService.sendMessageStream("c1", "What is AI?", null, received::add);

        // 验证调用顺序：用户消息 -> DeepSeek 流式 -> AI 消息
        InOrder inOrder = inOrder(mapper, deepSeek);
        inOrder.verify(mapper).insertMessage(argThat(m ->
            "user".equals(m.getRole()) && "What is AI?".equals(m.getContent())));
        inOrder.verify(deepSeek).chatStream(anyString(), anyString(), any());
        inOrder.verify(mapper).insertMessage(argThat(m ->
            "assistant".equals(m.getRole()) && "Hello World".equals(m.getContent())));

        // 验证 token 按顺序转发给回调
        assertEquals(List.of("Hello", " ", "World"), received);

        // 验证仅插入两条消息（用户 + AI），无重复
        verify(mapper, times(2)).insertMessage(any());
    }
}
