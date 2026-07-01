package com.exam.service;

import com.exam.mapper.DocumentChunkMapper;
import com.exam.mapper.DocumentMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.KnowledgePoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KnowledgeService {
    private final KnowledgeMapper mapper;
    private final DeepSeekService deepSeek;
    private final RAGService ragService;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentMapper docMapper;
    private final ObjectMapper json = new ObjectMapper();

    public KnowledgeService(KnowledgeMapper mapper, DeepSeekService deepSeek,
                            RAGService ragService, DocumentChunkMapper chunkMapper,
                            DocumentMapper docMapper) {
        this.mapper = mapper;
        this.deepSeek = deepSeek;
        this.ragService = ragService;
        this.chunkMapper = chunkMapper;
        this.docMapper = docMapper;
    }

    public List<KnowledgePoint> getByDocument(String documentId) {
        return mapper.findByDocumentId(documentId);
    }

    public List<KnowledgePoint> extract(String documentId) throws Exception {
        mapper.deleteByDocumentId(documentId);

        String structure = ragService.extractStructure(documentId);
        List<String> chunks = chunkMapper.getAllChunks(documentId);

        StringBuilder context = new StringBuilder();
        if (!structure.isEmpty()) {
            context.append(structure).append("\n\n");
        }

        if (!chunks.isEmpty()) {
            context.append("【文档正文】\n");
            int totalLen = 0;
            for (String chunk : chunks) {
                if (totalLen + chunk.length() > 12000) break;
                context.append(chunk).append("\n\n");
                totalLen += chunk.length();
            }
        } else {
            var doc = docMapper.findById(documentId);
            if (doc != null && doc.getContent() != null) {
                context.append(doc.getContent().substring(0, Math.min(doc.getContent().length(), 12000)));
            }
        }

        String prompt = "从以下文档提取关键知识点，数量根据内容长度灵活决定。严格返回JSON数组：[{\"title\":\"标题\",\"content\":\"详细说明\"}]";
        String resp = deepSeek.chat("你是知识管理专家。严格按JSON数组返回。", prompt + "\n\n" + context);

        String raw = resp.trim().replaceAll("```[\\\\w]*\\\\n?", "").replaceAll("```", "");
        List<Map<String, String>> points = json.readValue(raw, new TypeReference<>() {});

        List<KnowledgePoint> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            KnowledgePoint kp = new KnowledgePoint();
            kp.setId(UUID.randomUUID().toString().substring(0, 8));
            kp.setDocumentId(documentId);
            kp.setTitle(p.get("title"));
            kp.setContent(p.get("content"));
            kp.setOrder(i);
            mapper.insert(kp);
            result.add(kp);
        }
        return result;
    }
}