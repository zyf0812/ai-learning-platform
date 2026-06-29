package com.exam.controller;

import com.exam.service.StatsService;
import com.exam.mapper.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {
    private final StatsService service;
    private final UserMapper userMapper;
    public StatsController(StatsService s, UserMapper u) { this.service = s; this.userMapper = u; }

    @GetMapping
    public ResponseEntity<?> get(Authentication auth, @RequestParam(required = false) String userId) {
        String uid = userId != null ? userId : auth.getName();
        // 如果不是查自己，需要检查权限
        if (userId != null && !userId.equals(auth.getName())) {
            var u = userMapper.findById(auth.getName());
            if (u == null || (!"admin".equals(u.getRole()) && !"root".equals(u.getRole())))
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        return ResponseEntity.ok(Map.of("stats", service.get(uid)));
    }
}
