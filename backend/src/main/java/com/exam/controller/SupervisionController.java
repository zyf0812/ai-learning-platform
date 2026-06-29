package com.exam.controller;

import com.exam.mapper.*;
import com.exam.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/supervision")
public class SupervisionController {
    private final UserMapper userMapper;
    private final SupervisionMapper mapper;

    public SupervisionController(UserMapper u, SupervisionMapper m) { this.userMapper = u; this.mapper = m; }

    @GetMapping("/code")
    public ResponseEntity<?> getCode(Authentication auth) {
        var user = userMapper.findById(auth.getName());
        if (user == null || (!"admin".equals(user.getRole()) && !"root".equals(user.getRole())))
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可生成"));
        String code = user.getSuperviseCode();
        if (code == null) {
            code = String.valueOf(10000000 + (int)(Math.random() * 90000000));
            mapper.updateSuperviseCode(auth.getName(), code);
        }
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestBody Map<String, String> body, Authentication auth) {
        String code = body.get("code");
        if (code == null) return ResponseEntity.badRequest().body(Map.of("error", "请输入监管码"));
        var admin = mapper.findAdminByCode(code.trim());
        if (admin == null) return ResponseEntity.badRequest().body(Map.of("error", "监管码无效"));
        if (admin.get("id").equals(auth.getName())) return ResponseEntity.badRequest().body(Map.of("error", "不能监管自己"));
        var existing = mapper.findByUserAndAdmin(auth.getName(), (String)admin.get("id"));
        if (existing != null) return ResponseEntity.badRequest().body(Map.of("error", "已申请过"));
        mapper.insert(UUID.randomUUID().toString().substring(0,8), (String)admin.get("id"), auth.getName(), "pending");
        return ResponseEntity.ok(Map.of("message", "已申请监管，等待管理员批准"));
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication auth) {
        return ResponseEntity.ok(Map.of("users", mapper.findByAdmin(auth.getName())));
    }

    @PostMapping("/approve/{id}")
    public ResponseEntity<?> approve(@PathVariable String id) {
        mapper.updateStatus(id, "approved");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/reject/{id}")
    public ResponseEntity<?> reject(@PathVariable String id) {
        mapper.updateStatus(id, "rejected");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/remove/{id}")
    public ResponseEntity<?> remove(@PathVariable String id) {
        mapper.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
