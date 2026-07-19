package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.model.Document;
import com.exam.util.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@Service
@Transactional
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentMapper docMapper;
    private final DocumentParser parser;
    private final RAGService ragService;

    @Value("${app.upload.path:./uploads}")
    private String uploadPath;

    public DocumentService(DocumentMapper docMapper, DocumentParser parser, RAGService ragService) {
        this.docMapper = docMapper;
        this.parser = parser;
        this.ragService = ragService;
    }

    public List<Document> list(String userId) {
        return docMapper.findByUserId(userId);
    }

    public Document get(String id, String userId) {
        Document doc = docMapper.findById(id);
        if (doc != null && !doc.getUserId().equals(userId)) {
            throw new RuntimeException("无权访问该文档");
        }
        return doc;
    }

    public Document get(String id) {
        return docMapper.findById(id);
    }

    public Document upload(String userId, MultipartFile file, String title, String isQuestionBank) throws IOException {
        byte[] data = file.getBytes();
        String content = parser.parse(data, file.getOriginalFilename());
        String fileType = parser.getFileType(file.getOriginalFilename());

        // 保存原始文件
        File dir = new File(uploadPath);
        if (!dir.exists()) dir.mkdirs();
        Files.write(new File(dir, System.currentTimeMillis() + "-" + file.getOriginalFilename()).toPath(), data);

        Document doc = new Document();
        doc.setId(UUID.randomUUID().toString().substring(0, 8));
        doc.setTitle(title != null && !title.isBlank() ? title : file.getOriginalFilename().replaceAll("\\.[^.]+$", ""));
        doc.setOriginalFilename(file.getOriginalFilename());
        doc.setFileType(fileType);
        doc.setContent(content.length() > 500000 ? content.substring(0, 500000) : content);
        doc.setUserId(userId);
        doc.setIsQuestionBank("true".equals(isQuestionBank));

        docMapper.insert(doc);
        log.info("文档已保存: {} ({}字)", doc.getTitle(), content.length());

        // 后台 RAG 索引
        new Thread(() -> {
            try {
                int count = ragService.indexDocument(doc.getId(), content);
                log.info("RAG索引完成: {} 共{}块", doc.getId(), count);
            } catch (Exception e) {
                System.err.println("RAG indexing failed: " + e.getMessage());
            }
        }).start();

        return doc;
    }

    public void delete(String id, String userId) {
        Document doc = docMapper.findById(id);
        if (doc == null) throw new RuntimeException("文档不存在");
        if (!doc.getUserId().equals(userId)) throw new RuntimeException("无权删除该文档");
        docMapper.deleteById(id);
    }

    public void delete(String id) {
        docMapper.deleteById(id);
    }
}
