package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.ExamAttempt;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * StatsService 统计服务测试：
 * - 平均分计算（除零保护、空列表、四舍五入）
 * - 错题只计数未掌握的
 * - 各维度计数
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock private DocumentMapper docMapper;
    @Mock private ExamMapper examMapper;
    @Mock private FlashCardMapper fcMapper;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;

    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new StatsService(docMapper, examMapper, fcMapper, wqMapper, attemptMapper);
    }

    @Test
    void emptyUserAllZeros() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        var r = statsService.get("u1");

        assertEquals(0, r.get("documentCount"));
        assertEquals(0, r.get("examCount"));
        assertEquals(0, r.get("flashcardCount"));
        assertEquals(0, r.get("wrongCount"));
        assertEquals(0, r.get("attemptCount"));
        assertEquals(0.0, r.get("avgScore"));
    }

    @Test
    void avgScoreCalculatedRoundedToOneDecimal() {
        // attempt 1: 8/10 = 0.80, attempt 2: 6/10 = 0.60, attempt 3: 7/8 = 0.875
        // avg = (0.80 + 0.60 + 0.875) / 3 = 2.275 / 3 = 0.758333... -> *1000=758.333, round=758, /10=75.8
        ExamAttempt a1 = new ExamAttempt(); a1.setScore(8); a1.setTotal(10);
        ExamAttempt a2 = new ExamAttempt(); a2.setScore(6); a2.setTotal(10);
        ExamAttempt a3 = new ExamAttempt(); a3.setScore(7); a3.setTotal(8);
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2, a3));
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());

        var r = statsService.get("u1");

        assertEquals(3, r.get("attemptCount"));
        assertEquals(75.8, r.get("avgScore"));
    }

    @Test
    void avgScoreSkipsAttemptsWithZeroOrNullTotal() {
        // 防止除零：total=0 的考试不应参与计算
        ExamAttempt good = new ExamAttempt(); good.setScore(5); good.setTotal(10); // 0.5
        ExamAttempt zeroTot = new ExamAttempt(); zeroTot.setScore(0); zeroTot.setTotal(0); // 跳过
        ExamAttempt nullTot = new ExamAttempt(); nullTot.setScore(3); nullTot.setTotal(null); // 跳过
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(good, zeroTot, nullTot));
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());

        var r = statsService.get("u1");

        assertEquals(3, r.get("attemptCount")); // attemptCount 仍记录所有记录
        assertEquals(50.0, r.get("avgScore"));  // 平均分只算有效的 0.5 * 100
    }

    @Test
    void onlyNonMasteredWrongQuestionsCounted() {
        WrongQuestion w1 = new WrongQuestion(); w1.setMastered(false);
        WrongQuestion w2 = new WrongQuestion(); w2.setMastered(false);
        WrongQuestion w3 = new WrongQuestion(); w3.setMastered(true); // 已掌握不计
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(w1, w2, w3));
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        var r = statsService.get("u1");
        assertEquals(2, r.get("wrongCount"));
    }

    @Test
    void countersReflectMappers() {
        when(docMapper.findByUserId("u1")).thenReturn(java.util.Collections.nCopies(5, null));
        when(examMapper.findByUserId("u1")).thenReturn(java.util.Collections.nCopies(3, null));
        when(fcMapper.countByUserId("u1")).thenReturn(42);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        var r = statsService.get("u1");
        assertEquals(5, r.get("documentCount"));
        assertEquals(3, r.get("examCount"));
        assertEquals(42, r.get("flashcardCount"));
    }

    @Test
    void avgScorePerfectScore() {
        ExamAttempt a = new ExamAttempt(); a.setScore(10); a.setTotal(10); // 1.0 -> 100.0
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a));
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());

        var r = statsService.get("u1");
        assertEquals(100.0, r.get("avgScore"));
    }
}
