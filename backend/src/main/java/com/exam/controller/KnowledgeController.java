package com.exam.controller;

import com.exam.mapper.DocumentMapper;
import com.exam.service.KnowledgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/documents/{id}/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    private final DocumentMapper docMapper;

    public KnowledgeController(KnowledgeService svc, DocumentMapper docMapper) {
        this.service = svc;
        this.docMapper = docMapper;
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("points", service.getByDocument(id)));
    }

    @PostMapping
    public ResponseEntity<?> extract(@PathVariable String id) {
        try {
            return ResponseEntity.status(201)
                    .body(Map.of("points", service.extract(id)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
