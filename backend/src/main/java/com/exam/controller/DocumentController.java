package com.exam.controller;

import com.exam.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService docService;

    public DocumentController(DocumentService docService) {
        this.docService = docService;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok(Map.of("documents", docService.list(auth.getName())));
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestParam(value = "isQuestionBank", required = false) String isQuestionBank,
                                     Authentication auth) {
        try {
            return ResponseEntity.status(201)
                    .body(Map.of("document", docService.upload(auth.getName(), file, title, isQuestionBank)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, Authentication auth) {
        var doc = docService.get(id, auth.getName());
        if (doc == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("document", doc));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, Authentication auth) {
        docService.delete(id, auth.getName());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
