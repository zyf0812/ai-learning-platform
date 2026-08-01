package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock private DocumentMapper docMapper;
    @Mock private ExamMapper examMapper;
    @Mock private FlashCardMapper fcMapper;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;

    private StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(docMapper, examMapper, fcMapper, wqMapper, attemptMapper);
    }

    @Test
    void getEmptyStatsAllZeros() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> result = service.get("u1");

        assertEquals(0, result.get("documentCount"));
        assertEquals(0, result.get("examCount"));
        assertEquals(0, result.get("attemptCount"));
        assertEquals(0, result.get("avgScore"));
        assertEquals(0, result.get("flashcardCount"));
        assertEquals(0, result.get("wrongCount"));
    }

    @Test
    void getCountsDocumentsExamsFlashcards() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of(new Document(), new Document(), new Document()));
        when(examMapper.findByUserId("u1")).thenReturn(List.of(new Exam(), new Exam()));
        when(fcMapper.countByUserId("u1")).thenReturn(42);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> result = service.get("u1");

        assertEquals(3, result.get("documentCount"));
        assertEquals(2, result.get("examCount"));
        assertEquals(42, result.get("flashcardCount"));
    }

    @Test
    void getWrongQuestionsOnlyCountsUnmastered() {
        WrongQuestion w1 = new WrongQuestion();
        w1.setMastered(false);
        WrongQuestion w2 = new WrongQuestion();
        w2.setMastered(false);
        WrongQuestion w3 = new WrongQuestion();
        w3.setMastered(true); // 已掌握不计入
        WrongQuestion w4 = new WrongQuestion();
        w4.setMastered(false);

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(w1, w2, w3, w4));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> result = service.get("u1");
        assertEquals(3, result.get("wrongCount"));
    }

    @Test
    void getAvgScoreCalculatesCorrectlyWithRounding() {
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(8); a1.setTotal(10);  // 0.80
        ExamAttempt a2 = new ExamAttempt();
        a2.setScore(6); a2.setTotal(10);  // 0.60
        ExamAttempt a3 = new ExamAttempt();
        a3.setScore(9); a3.setTotal(10);  // 0.90
        // avg = (0.8+0.6+0.9)/3 = 0.766666... * 100 = 76.666... -> round/10 = 76.7

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2, a3));

        Map<String, Object> result = service.get("u1");

        assertEquals(3, result.get("attemptCount"));
        assertEquals(76.7, (Double) result.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreSkipsAttemptsWithZeroTotal() {
        // 防止除以0的total被跳过
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(5); a1.setTotal(10);  // 0.5
        ExamAttempt zeroTotal = new ExamAttempt();
        zeroTotal.setScore(0); zeroTotal.setTotal(0); // 应该被跳过
        ExamAttempt a2 = new ExamAttempt();
        a2.setScore(5); a2.setTotal(10);  // 0.5
        // avg = (0.5 + 0.5) / 2 = 0.5 -> 50.0

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, zeroTotal, a2));

        Map<String, Object> result = service.get("u1");
        assertEquals(3, result.get("attemptCount")); // attempt总数仍计3次
        assertEquals(50.0, (Double) result.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreNullTotalHandledSafely() {
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(10); a1.setTotal(20);  // 0.5
        ExamAttempt nullTotal = new ExamAttempt();
        nullTotal.setScore(5); nullTotal.setTotal(null); // 跳过

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, nullTotal));

        Map<String, Object> result = service.get("u1");
        assertEquals(2, result.get("attemptCount"));
        assertEquals(50.0, (Double) result.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreOnePerfectScore() {
        ExamAttempt a = new ExamAttempt();
        a.setScore(20); a.setTotal(20);

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a));

        Map<String, Object> result = service.get("u1");
        assertEquals(1, result.get("attemptCount"));
        assertEquals(100.0, (Double) result.get("avgScore"), 0.001);
    }

    @Test
    void getFullStatsScenario() {
        // 综合场景
        Document d1 = new Document(), d2 = new Document();
        Exam e1 = new Exam(), e2 = new Exam(), e3 = new Exam();
        WrongQuestion w1 = new WrongQuestion(); w1.setMastered(false);

        ExamAttempt a1 = new ExamAttempt(); a1.setScore(18); a1.setTotal(20); // 0.9
        ExamAttempt a2 = new ExamAttempt(); a2.setScore(14); a2.setTotal(20); // 0.7
        // avg = 0.8 -> 80.0

        when(docMapper.findByUserId("u1")).thenReturn(List.of(d1, d2));
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2, e3));
        when(fcMapper.countByUserId("u1")).thenReturn(100);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(w1));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2));

        Map<String, Object> result = service.get("u1");

        assertEquals(2, result.get("documentCount"));
        assertEquals(3, result.get("examCount"));
        assertEquals(100, result.get("flashcardCount"));
        assertEquals(1, result.get("wrongCount"));
        assertEquals(2, result.get("attemptCount"));
        assertEquals(80.0, (Double) result.get("avgScore"), 0.001);
    }
}
