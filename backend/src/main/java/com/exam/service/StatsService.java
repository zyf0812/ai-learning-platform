package com.exam.service;

import com.exam.mapper.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class StatsService {
    private final DocumentMapper docMapper;
    private final ExamMapper examMapper;
    private final FlashCardMapper fcMapper;
    private final WrongQuestionMapper wqMapper;
    private final ExamAttemptMapper attemptMapper;

    public StatsService(DocumentMapper d, ExamMapper e, FlashCardMapper f, WrongQuestionMapper w, ExamAttemptMapper a) {
        this.docMapper = d; this.examMapper = e; this.fcMapper = f; this.wqMapper = w; this.attemptMapper = a;
    }

    public Map<String, Object> get(String userId) {
        int docCount = docMapper.findByUserId(userId).size();
        int examCount = examMapper.findByUserId(userId).size();
        int fcCount = fcMapper.countByUserId(userId);
        int wrongCount = wqMapper.findByUserId(userId).stream().filter(w -> !w.getMastered()).toList().size();

        // 统计考试次数和均分
        int attemptCount = 0;
        double avgScore = 0;
        var attempts = attemptMapper.findByUserId(userId);
        if (attempts != null && !attempts.isEmpty()) {
            attemptCount = attempts.size();
            double sum = 0;
            for (var a : attempts) {
                if (a.getTotal() != null && a.getTotal() > 0) {
                    sum += (double) a.getScore() / a.getTotal();
                }
            }
            avgScore = Math.round(sum / attemptCount * 1000) / 10.0;
        }

        return Map.of(
            "documentCount", docCount,
            "examCount", examCount,
            "attemptCount", attemptCount,
            "avgScore", avgScore,
            "flashcardCount", fcCount,
            "wrongCount", wrongCount
        );
    }
}
