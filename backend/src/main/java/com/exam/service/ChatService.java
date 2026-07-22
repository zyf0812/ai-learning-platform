package com.exam.service;

import com.exam.mapper.ChatMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

@Service
@Transactional
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatMapper mapper;
    private final DeepSeekService deepSeek;
    private final DocumentMapper docMapper;
    private final RAGService ragService;
    private final ContextManager contextManager = new ContextManager();

    public ChatService(ChatMapper m, DeepSeekService d, DocumentMapper dm, RAGService rag) {
        this.mapper = m; this.deepSeek = d; this.docMapper = dm; this.ragService = rag;
    }

    public List<Conversation> listConversations(String userId) { return mapper.findByUserId(userId); }

    public Conversation createConversation(String userId, String title) {
        Conversation c = new Conversation();
        c.setId(UUID.randomUUID().toString().substring(0, 8));
        c.setTitle(title != null ? title : "新对话");
        c.setUserId(userId);
        mapper.insertConv(c);
        return c;
    }

    public Conversation getConversation(String id) { return mapper.findById(id); }

    public List<ChatMessage> getMessages(String convId) { return mapper.findMessages(convId); }

    public List<ChatMessage> getMessagesPaged(String convId, int limit, int offset) {
        return mapper.findMessagesPaged(convId, limit, offset);
    }

    public int countMessages(String convId) { return mapper.countMessages(convId); }

    public String sendMessage(String conversationId, String question, String documentId) throws Exception {
        // 校验对话是否存在且属于当前用户
        Conversation conv = mapper.findById(conversationId);
        if (conv == null) throw new RuntimeException("会话不存在");

        List<ChatMessage> history = mapper.findMessages(conversationId);

        // 1. RAG 检索相关文档知识
        List<String> ragChunks = new ArrayList<>();
        String docStructure = null;
        if (documentId != null && !documentId.isEmpty()) {
            try {
                ragChunks = ragService.searchByDocument(documentId, question, 20);
            } catch (Exception e) {
                log.warn("RAG search failed: {}", e.getMessage());
            }
            List<String> uniform = ragService.getChunksUniform(documentId, 3);
            // 去重：均匀采样和向量搜索结果可能有重复，以向量搜索优先
            Set<String> seen = new HashSet<>();
            for (String c : ragChunks) seen.add(c.length() > 50 ? c.substring(0, 50) : c);
            for (String c : uniform) {
                String key = c.length() > 50 ? c.substring(0, 50) : c;
                if (seen.add(key)) ragChunks.add(c);
            }
            // 文档结构（仅几百字符的骨架信息，token 极少）
            docStructure = ragService.extractStructure(documentId);
        }

        // 2. 历史过长时自动摘要压缩
        String summary = null;
        if (contextManager.needsSummary(history)) {
            List<ChatMessage> oldMessages = contextManager.getOldMessages(history);
            summary = summarize(oldMessages);
            log.info("对话 {} 生成摘要: {} 条旧消息压缩为 {} 字", conversationId, oldMessages.size(), summary.length());
        }

        // 3. 构建完整上下文
        String context = contextManager.buildContext(history, ragChunks, summary);
        if (docStructure != null && !docStructure.isEmpty()) {
            context = "【文档结构概览】\n" + docStructure + "\n\n" + context;
        }

        // 4. 发送给 DeepSeek
        String answer = deepSeek.chat(
            "你是一个AI学习助手。回答基于用户上传的文档内容。文档被切分为片段提供，各片段可能不连续，请综合所有片段信息来回答。如果片段信息不足以回答，说明缺少相关信息。\n\n" +
            "回答格式要求：\n" +
            "1. 使用标准 CommonMark Markdown 格式组织内容\n" +
            "2. 使用合适的标题（# ~ ######）区分层级\n" +
            "3. 使用列表（有序/无序）展示多条内容\n" +
            "4. 使用表格展示结构化数据\n" +
            "5. 使用引用块（>）强调重要内容\n" +
            "6. 使用代码块（```语言名）包裹代码，标注编程语言\n" +
            "7. 使用行内代码（`code`）标记技术术语\n" +
            "8. 使用粗体（**text**）强调重点\n" +
            "9. 代码块前后空一行，不要紧贴正文\n" +
            "10. 保持代码缩进和可读性\n" +
            "回答要专业、准确、有条理。",
            context + "\n\n用户: " + question
        );

        // 5. 存储消息
        ChatMessage qm = new ChatMessage(); qm.setId(UUID.randomUUID().toString().substring(0, 8));
        qm.setConversationId(conversationId); qm.setRole("user"); qm.setContent(question);
        qm.setCreatedAt(LocalDateTime.now());
        mapper.insertMessage(qm);

        ChatMessage am = new ChatMessage(); am.setId(UUID.randomUUID().toString().substring(0, 8));
        am.setConversationId(conversationId); am.setRole("assistant"); am.setContent(answer);
        am.setCreatedAt(LocalDateTime.now());
        mapper.insertMessage(am);

        return answer;
    }

    /** 调用 DeepSeek 将历史对话压缩为摘要 */
    private String summarize(List<ChatMessage> oldMessages) throws Exception {
        if (oldMessages.isEmpty()) return "";
        StringBuilder dialogs = new StringBuilder();
        for (ChatMessage m : oldMessages) {
            dialogs.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        return deepSeek.chat(
            "将以下对话历史压缩为100-200字的摘要，只保留关键信息和结论。",
            dialogs.toString()
        );
    }

    public void deleteConversation(String id) { mapper.deleteConv(id); }

    /**
     * 流式发送消息：逐 token 回调，最后保存完整回答
     */
    public void sendMessageStream(String conversationId, String question, String documentId,
                                  Consumer<String> onToken) throws Exception {
        Conversation conv = mapper.findById(conversationId);
        if (conv == null) throw new RuntimeException("会话不存在");

        List<ChatMessage> history = mapper.findMessages(conversationId);

        // 1. RAG 检索
        List<String> ragChunks = new ArrayList<>();
        String docStructure = null;
        if (documentId != null && !documentId.isEmpty()) {
            try {
                ragChunks = ragService.searchByDocument(documentId, question, 20);
            } catch (Exception e) {
                log.warn("RAG search failed: {}", e.getMessage());
            }
            List<String> uniform = ragService.getChunksUniform(documentId, 3);
            Set<String> seen = new HashSet<>();
            for (String c : ragChunks) seen.add(c.length() > 50 ? c.substring(0, 50) : c);
            for (String c : uniform) {
                String key = c.length() > 50 ? c.substring(0, 50) : c;
                if (seen.add(key)) ragChunks.add(c);
            }
            docStructure = ragService.extractStructure(documentId);
        }

        // 2. 历史摘要
        String summary = null;
        if (contextManager.needsSummary(history)) {
            List<ChatMessage> oldMessages = contextManager.getOldMessages(history);
            summary = summarize(oldMessages);
            log.info("对话 {} 生成摘要: {} 条旧消息压缩为 {} 字", conversationId, oldMessages.size(), summary.length());
        }

        // 3. 构建上下文
        String context = contextManager.buildContext(history, ragChunks, summary);
        if (docStructure != null && !docStructure.isEmpty()) {
            context = "【文档结构概览】\n" + docStructure + "\n\n" + context;
        }

        // 4. 先保存用户消息（时间戳早于 AI 回答）
        ChatMessage qm = new ChatMessage();
        qm.setId(UUID.randomUUID().toString().substring(0, 8));
        qm.setConversationId(conversationId);
        qm.setRole("user");
        qm.setContent(question);
        qm.setCreatedAt(LocalDateTime.now());
        mapper.insertMessage(qm);

        // 5. 流式调用 DeepSeek，逐 token 推给前端
        StringBuilder fullAnswer = new StringBuilder();
        deepSeek.chatStream(
            "你是一个AI学习助手。回答基于用户上传的文档内容。文档被切分为片段提供，各片段可能不连续，请综合所有片段信息来回答。如果片段信息不足以回答，说明缺少相关信息。回答要专业、准确、有条理。",
            context + "\n\n用户: " + question,
            token -> {
                fullAnswer.append(token);
                onToken.accept(token);
            }
        );

        // 6. 保存 AI 回答（时间戳晚于用户消息，排序自然正确）
        ChatMessage am = new ChatMessage();
        am.setId(UUID.randomUUID().toString().substring(0, 8));
        am.setConversationId(conversationId);
        am.setRole("assistant");
        am.setContent(fullAnswer.toString());
        am.setCreatedAt(LocalDateTime.now());
        mapper.insertMessage(am);
    }
}
