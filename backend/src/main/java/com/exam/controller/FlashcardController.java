package com.exam.controller;

import com.exam.dto.ReviewFlashcardRequest;
import com.exam.service.FlashcardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/flashcards")
public class FlashcardController {
    private final FlashcardService service;
    public FlashcardController(FlashcardService s) { this.service = s; }

    @GetMapping
    public ResponseEntity<?> get(Authentication auth) {
        return ResponseEntity.ok(service.getTodayCards(auth.getName()));
    }

    @PostMapping("/review")
    public ResponseEntity<?> review(@RequestBody ReviewFlashcardRequest body) {
        try {
            return ResponseEntity.ok(service.review(body.getCardId(), body.getQuality()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
