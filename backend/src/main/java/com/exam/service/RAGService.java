package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RAGService {
    private static final Logger log = LoggerFactory.getLogger(RAGService.class);

    private final DocumentChunkMapper chunkMapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private final String zhipuKey;
    private final String zhipuUrl;
    private final String embeddingModel;
    private final int embeddingDim;

    public RAGService(DocumentChunkMapper chunkMapper,
                      @Value("${app.zhipu.api-key}") String zhipuKey,
                      @Value("${app.zhipu.base-url}") String zhipuUrl,
                      @Value("${app.zhipu.embedding-model}") String embeddingModel,
                      @Value("${app.zhipu.embedding-dim:1024}") int embeddingDim) {
        this.chunkMapper = chunkMapper;
        this.zhipuKey = zhipuKey;
        this.zhipuUrl = zhipuUrl;
        this.embeddingModel = embeddingModel;
        this.embeddingDim = embeddingDim;
    }

    public List<String> chunk(String content, int chunkSize, int overlap) {
        String[] paragraphs = content.split("\n\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            // 超长段落按句子切分
            if (para.length() > chunkSize) {
                if (!current.isEmpty()) { chunks.add(current.toString().trim()); current = new StringBuilder(); }
                for (String s : para.split("(?<=[。！？.!?])\\s*")) {
                    if (current.length() + s.length() > chunkSize && !current.isEmpty()) {
                        chunks.add(current.toString().trim()); current = new StringBuilder();
                    }
                    current.append(s);
                }
                continue;
            }

            if (!current.isEmpty() && current.length() + para.length() > chunkSize) {
                chunks.add(current.toString().trim()); current = new StringBuilder();
            }
            current.append(para).append("\n\n");
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());

        // 加入 overlap：每个块尾部追加下一块开头的一小段，保持上下文连贯
        if (overlap > 0 && chunks.size() > 1) {
            List<String> overlapped = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                if (i < chunks.size() - 1) {
                    String next = chunks.get(i + 1);
                    int take = Math.min(overlap, next.length());
                    chunk += "\n\n" + next.substring(0, take);
                }
                overlapped.add(chunk);
            }
            return overlapped;
        }

        return chunks;
    }

    /** 调智谱 Embedding API */
    public float[] embed(String text) throws Exception {
        var body = Map.of("model", embeddingModel, "input", text);
        var req = new Request.Builder()
                .url(zhipuUrl + "/embeddings")
                .header("Authorization", "Bearer " + zhipuKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json.writeValueAsString(body), MediaType.parse("application/json")))
                .build();
        try (var resp = client.newCall(req).execute()) {
            var tree = json.readTree(resp.body().string());
            var emb = tree.get("data").get(0).get("embedding");
            float[] vec = new float[embeddingDim];
            for (int i = 0; i < embeddingDim; i++) vec[i] = emb.get(i).floatValue();
            return vec;
        }
    }

    public String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 索引文档到 pgvector */
    public int indexDocument(String documentId, String content) {
        List<String> chunks = chunk(content, 1024, 128);
        chunkMapper.deleteByDocumentId(documentId);
        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                float[] vec = embed(chunks.get(i));
                String vecStr = toVectorString(vec);
                chunkMapper.insert(documentId + "_chunk_" + i, documentId, i, chunks.get(i), vecStr);
                indexed++;
            } catch (Exception e) {
                log.error("Chunk {} index failed: {}", i, e.getMessage());
            }
        }
        log.info("RAG indexed {} chunks for doc {}", indexed, documentId);
        return indexed;
    }

    /** 在指定文档内向量搜索 */
    public List<String> searchByDocument(String documentId, String query, int topK) {
        try {
            float[] vec = embed(query.isEmpty() ? documentId : query);
            return chunkMapper.searchByDocument(documentId, toVectorString(vec), topK);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 按文档顺序获取片段（用于出题等需均匀覆盖的场景） */
    public List<String> getChunksByIndex(String documentId, int limit) {
        try {
            return chunkMapper.getChunksByIndex(documentId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 均匀采样文档片段（用于全文概览类问题） */
    public List<String> getChunksUniform(String documentId, int step) {
        try {
            return chunkMapper.getChunksUniform(documentId, step);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 随机取文档片段（用于出题，每次不同知识点） */
    List<String> getChunksRandom(String documentId, int limit) {
        try {
            return chunkMapper.getChunksRandom(documentId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 提取文档结构：开头介绍 + 标题/小标题列表（仅几百字符，token 极少） */
    public String extractStructure(String documentId) {
        try {
            List<String> all = chunkMapper.getAllChunks(documentId);
            if (all.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            // 开头：取第一个块的前 500 字符
            String first = all.get(0).trim();
            sb.append("【文档开头】\n").append(first.length() > 500 ? first.substring(0, 500) : first).append("\n\n");
            // 标题：找短块（< 200 字符且不含换行，像章节标题）
            List<String> headings = new ArrayList<>();
            for (String c : all) {
                String t = c.trim();
                if (t.length() > 10 && t.length() < 200 && !t.contains("\n")) {
                    headings.add(t);
                }
            }
            if (!headings.isEmpty()) {
                sb.append("【章节标题】\n");
                for (String h : headings) sb.append("· ").append(h).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 全局向量搜索 */
    public List<String> searchAll(String query, int topK) {
        try {
            float[] vec = embed(query);
            return chunkMapper.searchAll(toVectorString(vec), topK);
        } catch (Exception e) {
            return List.of();
        }
    }
}
