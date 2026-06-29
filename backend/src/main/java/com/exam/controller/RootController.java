package com.exam.controller;

import com.exam.mapper.*;
import com.exam.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/root")
public class RootController {
    private final UserMapper userMapper;
    private final DocumentMapper docMapper;
    private final ExamMapper examMapper;
    private final ChatMapper chatMapper;

    public RootController(UserMapper u, DocumentMapper d, ExamMapper e, ChatMapper c) {
        this.userMapper = u; this.docMapper = d; this.examMapper = e; this.chatMapper = c;
    }

    private boolean isRoot(Authentication auth) {
        User u = userMapper.findById(auth.getName());
        return u != null && "root".equals(u.getRole());
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        return ResponseEntity.ok(Map.of("users", userMapper.findAll()));
    }

    @GetMapping("/admins/pending")
    public ResponseEntity<?> pendingAdmins(Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        var admins = userMapper.findByRole("admin").stream().filter(u -> "pending".equals(u.getStatus())).toList();
        return ResponseEntity.ok(Map.of("users", admins));
    }

    @PostMapping("/admins/{id}/approve")
    public ResponseEntity<?> approveAdmin(@PathVariable String id, Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        userMapper.updateRoleStatus(id, "admin", "active");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/admins/{id}/reject")
    public ResponseEntity<?> rejectAdmin(@PathVariable String id, Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        userMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id, Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        userMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/users/{id}/documents")
    public ResponseEntity<?> userDocuments(@PathVariable String id, Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        return ResponseEntity.ok(Map.of("documents", docMapper.findByUserId(id)));
    }

    @GetMapping("/users/{id}/exams")
    public ResponseEntity<?> userExams(@PathVariable String id, Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        return ResponseEntity.ok(Map.of("exams", examMapper.findByUserId(id)));
    }

    @GetMapping("/users/{id}/chats")
    public ResponseEntity<?> userChats(@PathVariable String id, Authentication auth) {
        if (!isRoot(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        return ResponseEntity.ok(Map.of("conversations", chatMapper.findByUserId(id)));
    }
}
