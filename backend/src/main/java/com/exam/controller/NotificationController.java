package com.exam.controller;

import com.exam.mapper.NotificationMapper;
import com.exam.mapper.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationMapper notifMapper;
    private final UserMapper userMapper;

    public NotificationController(NotificationMapper n, UserMapper u) { this.notifMapper = n; this.userMapper = u; }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok(Map.of(
            "notifications", notifMapper.findForUser(auth.getName()),
            "unread", notifMapper.countUnread(auth.getName())
        ));
    }

    @PostMapping("/read/{id}")
    public ResponseEntity<?> read(@PathVariable String id) {
        notifMapper.markRead(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // 管理员给普通用户发通知
    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody Map<String, Object> body, Authentication auth) {
        var user = userMapper.findById(auth.getName());
        String role = user != null ? user.getRole() : "";
        if (!"admin".equals(role) && !"root".equals(role))
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));

        String title = (String) body.get("title");
        String content = (String) body.get("content");
        List<String> userIds = (List<String>) body.getOrDefault("userIds", List.of());

        for (String uid : userIds) {
            notifMapper.insertDirect(UUID.randomUUID().toString().substring(0, 8), auth.getName(), uid, title, content);
        }
        return ResponseEntity.ok(Map.of("success", true, "sent", userIds.size()));
    }

    // Root 全局广播
    @PostMapping("/broadcast")
    public ResponseEntity<?> broadcast(@RequestBody Map<String, String> body, Authentication auth) {
        var user = userMapper.findById(auth.getName());
        if (user == null || !"root".equals(user.getRole()))
            return ResponseEntity.status(403).body(Map.of("error", "仅 root 可广播"));

        notifMapper.insertBroadcast(UUID.randomUUID().toString().substring(0, 8), auth.getName(),
                body.get("title"), body.get("content"));
        return ResponseEntity.ok(Map.of("success", true));
    }
}
