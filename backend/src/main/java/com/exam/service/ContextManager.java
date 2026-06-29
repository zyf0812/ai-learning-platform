package com.exam.service;

import com.exam.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 智能上下文管理器
 * 
 * 亮点：
 * 1. 窗口消息保留最近 20 条原文
 * 2. 超出的历史消息自动调用 DeepSeek 生成摘要压缩
 * 3. 上下文注入：文档 RAG 检索 + 历史摘要 + 最近对话
 * 4. 动态 token 预算管理，避免超限
 */
public class ContextManager {

    private static final int RECENT_COUNT = 20;      // 保留最近 N 条原文
    private static final int MAX_TOKENS = 8000;       // 总 token 预算

    /**
     * 构建上下文
     * @param history 全部历史消息
     * @param ragChunks RAG 检索出的相关文档片段
     * @param summary 历史对话摘要（可为空）
     * @return 组装好的上下文字符串
     */
    public String buildContext(List<ChatMessage> history, List<String> ragChunks, String summary) {
        StringBuilder ctx = new StringBuilder();

        // 1. 文档知识（RAG）
        if (ragChunks != null && !ragChunks.isEmpty()) {
            ctx.append("【相关知识】\n");
            for (String chunk : ragChunks) {
                ctx.append("· ").append(chunk).append("\n");
            }
            ctx.append("\n");
        }

        // 2. 历史摘要（压缩的旧对话）
        if (summary != null && !summary.isEmpty()) {
            ctx.append("【对话背景】\n").append(summary).append("\n\n");
        }

        // 3. 最近对话
        if (history != null && !history.isEmpty()) {
            List<ChatMessage> recent = history;
            if (history.size() > RECENT_COUNT) {
                recent = history.subList(history.size() - RECENT_COUNT, history.size());
            }
            ctx.append("【最近对话】\n");
            for (ChatMessage m : recent) {
                ctx.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
            }
        }

        return trimToTokens(ctx.toString(), MAX_TOKENS);
    }

    /** 需要摘要的条件：消息超过阈值 */
    public boolean needsSummary(List<ChatMessage> history) {
        return history != null && history.size() > RECENT_COUNT + 10;
    }

    /** 获取需要摘要的部分（超出最近 N 条的旧消息） */
    public List<ChatMessage> getOldMessages(List<ChatMessage> history) {
        if (history == null || history.size() <= RECENT_COUNT) return List.of();
        return history.subList(0, history.size() - RECENT_COUNT);
    }

    /** 简单的 token 估算：中文约每字 1 token，英文约每词 1 token */
    private String trimToTokens(String text, int maxTokens) {
        if (text.length() < maxTokens) return text;
        return text.substring(0, maxTokens) + "\n...(上下文过长已截断)";
    }
}
