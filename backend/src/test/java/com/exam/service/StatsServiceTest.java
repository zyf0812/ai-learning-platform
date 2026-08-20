package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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

    private ExamAttempt attempt(int score, int total) {
        ExamAttempt a = new ExamAttempt();
        a.setId("a" + System.nanoTime());
        a.setScore(score);
        a.setTotal(total);
        a.setCompletedAt(LocalDateTime.now());
        return a;
    }

    private WrongQuestion wq(boolean mastered) {
        WrongQuestion w = new WrongQuestion();
        w.setMastered(mastered);
        return w;
    }

    @Test
    void getReturnsCounts() {
        Document d1 = new Document();
        Exam e1 = new Exam();
        when(docMapper.findByUserId("u1")).thenReturn(List.of(d1));
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1));
        when(fcMapper.countByUserId("u1")).thenReturn(5);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(wq(false), wq(true), wq(false)));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(
            attempt(80, 100),   // 0.80
            attempt(50, 100)    // 0.50
        ));
        // avg = (0.8 + 0.5)/2 = 0.65 → *100 → 65.0 after round(0.65 * 1000) / 10.0
        // wait: 0.65 * 1000 = 650, round = 650, /10 = 65.0
        Map<String, Object> r = service.get("u1");

        assertEquals(1, r.get("documentCount"));
        assertEquals(1, r.get("examCount"));
        assertEquals(5, r.get("flashcardCount"));
        assertEquals(2, r.get("wrongCount")); // 2 unmastered
        assertEquals(2, r.get("attemptCount"));
        assertEquals(65.0, r.get("avgScore"));
    }

    @Test
    void getEmptyEverythingZeros() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> r = service.get("u1");
        assertEquals(0, r.get("documentCount"));
        assertEquals(0, r.get("examCount"));
        assertEquals(0, r.get("flashcardCount"));
        assertEquals(0, r.get("wrongCount"));
        assertEquals(0, r.get("attemptCount"));
        assertEquals(0.0, (Double) r.get("avgScore"), 0.001);
    }

    @Test
    void getNullAttemptListCountsAsZero() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(null);

        Map<String, Object> r = service.get("u1");
        assertEquals(0, r.get("attemptCount"));
        assertEquals(0.0, (Double) r.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreIgnoresZeroTotalAttempts() {
        // If total is 0 or null, score/total is skipped
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());

        ExamAttempt zeroTotal = attempt(10, 0);
        ExamAttempt validAttempt = attempt(70, 100); // 0.70
        ExamAttempt nullTotal = new ExamAttempt();
        nullTotal.setScore(100);
        nullTotal.setTotal(null);

        when(attemptMapper.findByUserId("u1")).thenReturn(
            List.of(zeroTotal, validAttempt, nullTotal));

        Map<String, Object> r = service.get("u1");
        // only the 0.70 valid one; attemptCount still counts all 3
        assertEquals(3, r.get("attemptCount"));
        assertEquals(23.3, (Double) r.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreRoundsToOneDecimal() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());

        // 1/3 = 0.3333... → *1000 → 333.333... → round → 333 → /10 = 33.3
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(attempt(1, 3)));

        Map<String, Object> r = service.get("u1");
        assertEquals(33.3, r.get("avgScore"));
    }

    @Test
    void getWrongCountOnlyCountsUnmastered() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        when(wqMapper.findByUserId("u1")).thenReturn(List.of(
            wq(true), wq(true), wq(true), wq(false)
        ));

        Map<String, Object> r = service.get("u1");
        assertEquals(1, r.get("wrongCount"));
    }
}