package com.exam.controller;

import com.exam.dto.CreateConversationRequest;
import com.exam.dto.SendMessageRequest;
import com.exam.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {
    private final ChatService service;
    public ChatController(ChatService s) { this.service = s; }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok(Map.of("conversations", service.listConversations(auth.getName())));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateConversationRequest body, Authentication auth) {
        return ResponseEntity.status(201).body(Map.of("conversation",
            service.createConversation(auth.getName(), body.getTitle())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        var conv = service.getConversation(id);
        if (conv == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("conversation",
            Map.of("id", conv.getId(), "title", conv.getTitle(), "messages", service.getMessages(id))));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable String id,
                                       @RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        var msgs = service.getMessagesPaged(id, limit, offset);
        int total = service.countMessages(id);
        return ResponseEntity.ok(Map.of("messages", msgs, "total", total, "hasMore", offset + limit < total));
    }

    @PostMapping("/{id}")
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody SendMessageRequest body) {
        try {
            return ResponseEntity.ok(Map.of("answer", service.sendMessage(id, body.getQuestion(), body.getRefDoc())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        service.deleteConversation(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
