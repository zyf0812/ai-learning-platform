package com.exam.controller;

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
    public ResponseEntity<?> review(@RequestBody Map<String, Object> body) {
        try {
            String cardId = (String) body.get("cardId");
            int quality = ((Number) body.get("quality")).intValue();
            return ResponseEntity.ok(service.review(cardId, quality));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
