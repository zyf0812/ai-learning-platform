package com.exam.controller;

import com.exam.mapper.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api/groups")
public class GroupController {
    private final GroupMapper mapper;
    private final UserMapper userMapper;
    private final GroupChatMapper chatMapper;

    @Value("${app.upload.path:./uploads}")
    private String uploadPath;

    public GroupController(GroupMapper m, UserMapper u, GroupChatMapper c) {
        this.mapper = m; this.userMapper = u; this.chatMapper = c;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok(Map.of("groups", mapper.findByUserId(auth.getName())));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body, Authentication auth) {
        var user = userMapper.findById(auth.getName());
        if (user == null || (!"admin".equals(user.getRole()) && !"root".equals(user.getRole())))
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可创建群组"));

        String name = body.get("name");
        if (name == null || name.trim().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "请输入群名称"));

        String id = UUID.randomUUID().toString().substring(0, 8);
        String code = generateCode();
        mapper.insertGroup(id, name.trim(), code, auth.getName());
        return ResponseEntity.status(201).body(Map.of("group", Map.of("id", id, "name", name.trim(), "code", code)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        var g = mapper.findById(id);
        if (g == null) return ResponseEntity.notFound().build();
        g.put("members", mapper.findMembers(id));
        return ResponseEntity.ok(Map.of("group", g));
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestBody Map<String, String> body, Authentication auth) {
        String code = body.get("code");
        if (code == null) return ResponseEntity.badRequest().body(Map.of("error", "请输入加入码"));
        var g = mapper.findByCode(code.trim());
        if (g == null) return ResponseEntity.badRequest().body(Map.of("error", "群组不存在或加入码错误"));

        String groupId = (String) g.get("id");
        var existing = mapper.findMember(groupId, auth.getName());
        if (existing != null) return ResponseEntity.badRequest().body(Map.of("error", "你已经申请过此群组"));

        String mid = UUID.randomUUID().toString().substring(0, 8);
        mapper.insertMember(mid, groupId, auth.getName(), "pending");
        return ResponseEntity.ok(Map.of("message", "申请已提交，等待管理员审批"));
    }

    @PostMapping("/{id}/approve/{memberId}")
    public ResponseEntity<?> approve(@PathVariable String id, @PathVariable String memberId, Authentication auth) {
        var g = mapper.findById(id);
        if (g == null || !auth.getName().equals(g.get("adminUserId")))
            return ResponseEntity.status(403).body(Map.of("error", "仅群主可审批"));
        mapper.updateMemberStatus(memberId, "approved");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/reject/{memberId}")
    public ResponseEntity<?> reject(@PathVariable String id, @PathVariable String memberId, Authentication auth) {
        var g = mapper.findById(id);
        if (g == null || !auth.getName().equals(g.get("adminUserId")))
            return ResponseEntity.status(403).body(Map.of("error", "仅群主可操作"));
        mapper.updateMemberStatus(memberId, "rejected");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/remove/{memberId}")
    public ResponseEntity<?> remove(@PathVariable String id, @PathVariable String memberId, Authentication auth) {
        var g = mapper.findById(id);
        if (g == null || !auth.getName().equals(g.get("adminUserId")))
            return ResponseEntity.status(403).body(Map.of("error", "仅群主可操作"));
        mapper.deleteMember(memberId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> disband(@PathVariable String id, Authentication auth) {
        var g = mapper.findById(id);
        if (g == null || !auth.getName().equals(g.get("adminUserId")))
            return ResponseEntity.status(403).body(Map.of("error", "仅群主可解散"));
        
        // 清理上传的文件
        try {
            File groupDir = new File(uploadPath + "/groups");
            if (groupDir.exists()) {
                var messages = chatMapper.findMessages(id);
                for (var msg : messages) {
                    String fn = (String) msg.get("fileName");
                    if (fn != null) {
                        String url = (String) msg.get("fileUrl");
                        if (url != null) {
                            File f = new File(uploadPath + "/groups", new File(url).getName());
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        
        mapper.deleteGroup(id); // CASCADE 会删消息和成员
        return ResponseEntity.ok(Map.of("success", true));
    }

    private String generateCode() {
        return String.valueOf(10000000 + (int) (Math.random() * 90000000));
    }
}
