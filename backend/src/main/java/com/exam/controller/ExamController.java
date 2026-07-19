package com.exam.controller;

import com.exam.dto.GenerateExamRequest;
import com.exam.dto.SubmitExamRequest;
import com.exam.service.ExamService;
import jakarta.validation.Valid;
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
    public ResponseEntity<?> get(@PathVariable String id, Authentication auth) {
        var exam = service.get(id, auth.getName());
        return exam != null ? ResponseEntity.ok(Map.of("exam", exam)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, Authentication auth) {
        service.delete(id, auth.getName());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerateExamRequest body, Authentication auth) {
        var result = service.generateAsync(auth.getName(), body.getTitle(), body.getDocumentIds(),
            body.getTypes(), body.getCount(), body.getTypeCounts(), body.isQuestionBankMode());
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        String status = service.getTaskStatus(id);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", status));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable String id, @RequestBody SubmitExamRequest body,
                                     Authentication auth) {
        var result = service.submit(id, auth.getName(), body.getAnswers());
        return ResponseEntity.ok(Map.of("attempt", result));
    }
}
