package com.exam.service;

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
    private final ObjectMapper json = new ObjectMapper();

    public KnowledgeService(KnowledgeMapper mapper, DeepSeekService deepSeek) {
        this.mapper = mapper;
        this.deepSeek = deepSeek;
    }

    public List<KnowledgePoint> getByDocument(String documentId) {
        return mapper.findByDocumentId(documentId);
    }

    public List<KnowledgePoint> extract(String documentId, String content) throws Exception {
        mapper.deleteByDocumentId(documentId);

        String prompt = "从以下文档提取8-15个关键知识点。严格返回JSON数组：[{\"title\":\"标题\",\"content\":\"详细说明\"}]";
        String resp = deepSeek.chat("你是知识管理专家。严格按JSON数组返回。", prompt + "\n\n" + content.substring(0, Math.min(content.length(), 15000)));

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
