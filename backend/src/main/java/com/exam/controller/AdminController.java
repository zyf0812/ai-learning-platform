package com.exam.controller;

import com.exam.mapper.*;
import com.exam.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final UserMapper userMapper;
    private final ExamMapper examMapper;
    private final WrongQuestionMapper wqMapper;
    private final QuestionMapper questionMapper;
    private final GroupMapper groupMapper;
    private final SupervisionMapper supervisionMapper;

    public AdminController(UserMapper u, ExamMapper e, WrongQuestionMapper w, QuestionMapper q, GroupMapper g, SupervisionMapper s) {
        this.userMapper = u; this.examMapper = e; this.wqMapper = w; this.questionMapper = q; this.groupMapper = g; this.supervisionMapper = s;
    }

    private boolean isAdmin(Authentication auth) {
        User u = userMapper.findById(auth.getName());
        return u != null && ("admin".equals(u.getRole()) || "root".equals(u.getRole()));
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        var currentUser = userMapper.findById(auth.getName());
        List<Map<String, Object>> users;
        if ("root".equals(currentUser.getRole())) {
            // Root 看所有普通用户
            var allUsers = userMapper.findByRole("user");
            users = allUsers.stream().map(u -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", u.getId());
                m.put("username", u.getUsername());
                return m;
            }).toList();
        } else {
            // 管理员只看已批准的监管用户
            var allUsers = supervisionMapper.findByAdmin(auth.getName());
            users = allUsers.stream().filter(u -> "approved".equals(u.get("status"))).map(u -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", u.get("userId"));
                m.put("username", u.get("username"));
                return m;
            }).toList();
        }
        return ResponseEntity.ok(Map.of("users", users));
    }

    @GetMapping("/users/{id}/exams")
    public ResponseEntity<?> userExams(@PathVariable String id, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        return ResponseEntity.ok(Map.of("exams", examMapper.findByUserId(id)));
    }

    @GetMapping("/users/{id}/wrong-questions")
    public ResponseEntity<?> userWrongQuestions(@PathVariable String id, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        var wqs = wqMapper.findByUserId(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var wq : wqs) {
            var m = new HashMap<String, Object>();
            m.put("id", wq.getId());
            m.put("userAnswer", wq.getUserAnswer());
            m.put("mastered", wq.getMastered());
            var q = questionMapper.findById(wq.getQuestionId());
            if (q != null) {
                m.put("content", q.getContent());
                m.put("answer", q.getAnswer());
                m.put("explanation", q.getExplanation());
                m.put("type", q.getType());
            }
            result.add(m);
        }
        return ResponseEntity.ok(Map.of("wrongQuestions", result));
    }
}
