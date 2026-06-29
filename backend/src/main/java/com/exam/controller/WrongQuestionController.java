package com.exam.controller;

import com.exam.mapper.WrongQuestionMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.model.Question;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/wrong-questions")
public class WrongQuestionController {
    private final WrongQuestionMapper wqMapper;
    private final QuestionMapper qMapper;

    public WrongQuestionController(WrongQuestionMapper w, QuestionMapper q) { this.wqMapper = w; this.qMapper = q; }

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        var wqs = wqMapper.findByUserId(auth.getName());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var wq : wqs) {
            var m = new HashMap<String, Object>();
            m.put("id", wq.getId());
            m.put("questionId", wq.getQuestionId());
            m.put("userAnswer", wq.getUserAnswer());
            m.put("mastered", wq.getMastered());
            m.put("reviewCount", wq.getReviewCount());
            // 查题目内容
            var q = qMapper.findById(wq.getQuestionId());
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

    @PatchMapping
    public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
        wqMapper.updateMastered((String) body.get("id"), (Boolean) body.get("mastered"));
        return ResponseEntity.ok(Map.of("success", true));
    }
}
