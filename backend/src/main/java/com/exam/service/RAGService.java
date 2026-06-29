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
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            String chunk = content.substring(start, end).trim();
            if (chunk.length() > 10) chunks.add(chunk);
            start += (chunkSize - overlap);
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
        List<String> chunks = chunk(content, 512, 64);
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
