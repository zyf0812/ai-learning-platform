package com.exam.controller;

import com.exam.service.ExamService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/exams")
public class ExamController {
    private final ExamService service;

    public ExamController(ExamService svc) {
        this.service = svc;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok(Map.of("exams", service.list(auth.getName())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        var exam = service.get(id);
        return exam != null ? ResponseEntity.ok(Map.of("exam", exam)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body, Authentication auth) {
        String title = (String) body.get("title");
        List<String> docIds = (List<String>) body.get("documentIds");
        List<String> types = (List<String>) body.get("types");
        int count = ((Number) body.get("count")).intValue();
        @SuppressWarnings("unchecked")
        Map<String, Integer> typeCounts = body.containsKey("typeCounts") 
            ? (Map<String, Integer>) body.get("typeCounts") : null;
        boolean questionBankMode = body.getOrDefault("questionBankMode", false) instanceof Boolean b ? b : false;
        var result = service.generateAsync(auth.getName(), title, docIds, types, count, typeCounts, questionBankMode);
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        String status = service.getTaskStatus(id);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", status));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable String id, @RequestBody Map<String, Object> body,
                                     Authentication auth) {
        @SuppressWarnings("unchecked")
        Map<String, String> answers = (Map<String, String>) body.get("answers");
        var result = service.submit(id, auth.getName(), answers);
        return ResponseEntity.ok(Map.of("attempt", result));
    }
}
