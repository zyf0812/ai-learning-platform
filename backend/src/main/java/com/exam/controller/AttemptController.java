package com.exam.controller;

import com.exam.mapper.ExamAttemptMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/attempts")
public class AttemptController {
    private final ExamAttemptMapper mapper;

    public AttemptController(ExamAttemptMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam("examId") String examId, Authentication auth) {
        var attempts = mapper.findByExamAndUser(examId, auth.getName());
        return ResponseEntity.ok(Map.of("attempts", attempts));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        var a = mapper.findById(id);
        if (a == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("attempt", a));
    }
}
