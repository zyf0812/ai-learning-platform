package com.exam.controller;

import com.exam.mapper.*;
import com.exam.model.Document;
import com.exam.util.DocumentParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/groups/{id}/chat")
public class GroupChatController {
    private final GroupChatMapper chatMapper;
    private final UserMapper userMapper;
    private final GroupMapper groupMapper;
    private final NotificationMapper notifMapper;

    @Value("${app.upload.path:./uploads}")
    private String uploadPath;

    private final DocumentMapper docMapper;
    private final DocumentParser parser;

    public GroupChatController(GroupChatMapper c, UserMapper u, GroupMapper g, NotificationMapper n, DocumentMapper docMapper, DocumentParser parser) {
        this.chatMapper = c; this.userMapper = u; this.groupMapper = g; this.notifMapper = n;
        this.docMapper = docMapper; this.parser = parser;
    }

    @GetMapping
    public ResponseEntity<?> messages(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("messages", chatMapper.findMessages(id)));
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody Map<String, String> body, Authentication auth) {
        String content = body.get("content");
        if (content == null || content.trim().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "消息不能为空"));

        var user = userMapper.findById(auth.getName());
        String mid = UUID.randomUUID().toString().substring(0, 8);
        chatMapper.insertMessage(mid, id, auth.getName(), user.getUsername(), content.trim(), null, null);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@PathVariable String id, @RequestParam("file") MultipartFile file, Authentication auth) {
        try {
            var user = userMapper.findById(auth.getName());
            String role = user != null ? user.getRole() : "";
            if (!"admin".equals(role) && !"root".equals(role))
                return ResponseEntity.status(403).body(Map.of("error", "仅管理员可上传文件"));

            File dir = new File(uploadPath + "/groups");
            if (!dir.exists()) dir.mkdirs();
            String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Files.write(Paths.get(dir.getPath(), filename), file.getBytes());

            String mid = UUID.randomUUID().toString().substring(0, 8);
            chatMapper.insertMessage(mid, id, auth.getName(), user.getUsername(),
                    "📎 上传了文件: " + file.getOriginalFilename(), file.getOriginalFilename(), "/uploads/groups/" + filename);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/save-file")
    public ResponseEntity<?> saveFile(@PathVariable String id, @RequestBody Map<String, String> body, Authentication auth) {
        String fileName = body.get("fileName");
        String fileUrl = body.get("fileUrl");
        if (fileName == null || fileUrl == null) return ResponseEntity.badRequest().body(Map.of("error", "参数不完整"));

        try {
            // 读取文件内容并解析
            File file = new File(uploadPath + "/groups", new File(fileUrl).getName());
            String content = "";
            if (file.exists()) {
                byte[] data = Files.readAllBytes(file.toPath());
                content = parser.parse(data, fileName);
            }

            var doc = new Document();
            doc.setId(UUID.randomUUID().toString().substring(0, 8));
            doc.setTitle(fileName);
            doc.setOriginalFilename(fileName);
            doc.setFileType(parser.getFileType(fileName));
            doc.setContent(content.length() > 500000 ? content.substring(0, 500000) : content);
            doc.setUserId(auth.getName());
            docMapper.insert(doc);
            return ResponseEntity.ok(Map.of("success", true, "documentId", doc.getId(), "size", content.length()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/notify")
    public ResponseEntity<?> notify(@PathVariable String id, @RequestBody Map<String, Object> body, Authentication auth) {
        var user = userMapper.findById(auth.getName());
        String role = user != null ? user.getRole() : "";
        if (!"admin".equals(role) && !"root".equals(role))
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可发通知"));

        String title = (String) body.get("title");
        String content = (String) body.get("content");
        @SuppressWarnings("unchecked")
        List<String> userIds = (List<String>) body.getOrDefault("userIds", List.of());

        for (String uid : userIds) {
            notifMapper.insertDirect(UUID.randomUUID().toString().substring(0, 8), auth.getName(), uid, title, content);
        }
        return ResponseEntity.ok(Map.of("success", true, "sent", userIds.size()));
    }
}
