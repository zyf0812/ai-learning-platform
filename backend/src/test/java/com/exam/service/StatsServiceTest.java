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

    @Mock
    private DocumentMapper docMapper;
    @Mock
    private ExamMapper examMapper;
    @Mock
    private FlashCardMapper fcMapper;
    @Mock
    private WrongQuestionMapper wqMapper;
    @Mock
    private ExamAttemptMapper attemptMapper;

    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new StatsService(docMapper, examMapper, fcMapper, wqMapper, attemptMapper);
    }

    @Test
    void allEmptyReturnsZeros() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        var result = statsService.get("u1");

        assertEquals(0, result.get("documentCount"));
        assertEquals(0, result.get("examCount"));
        assertEquals(0, result.get("attemptCount"));
        assertEquals(0.0, result.get("avgScore"));
        assertEquals(0, result.get("flashcardCount"));
        assertEquals(0, result.get("wrongCount"));
    }

    @Test
    void documentCountReflectsMapperResult() {
        Document d1 = new Document(); d1.setId("d1");
        Document d2 = new Document(); d2.setId("d2");
        Document d3 = new Document(); d3.setId("d3");
        when(docMapper.findByUserId("u1")).thenReturn(List.of(d1, d2, d3));
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        assertEquals(3, statsService.get("u1").get("documentCount"));
    }

    @Test
    void flashcardCountFromMapper() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(42);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        assertEquals(42, statsService.get("u1").get("flashcardCount"));
    }

    @Test
    void wrongCountExcludesMastered() {
        WrongQuestion w1 = new WrongQuestion(); w1.setMastered(false);
        WrongQuestion w2 = new WrongQuestion(); w2.setMastered(true); // excluded
        WrongQuestion w3 = new WrongQuestion(); w3.setMastered(false);
        WrongQuestion w4 = new WrongQuestion(); w4.setMastered(false);

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(w1, w2, w3, w4));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        assertEquals(3, statsService.get("u1").get("wrongCount"));
    }

    @Test
    void avgScoreSingleAttemptFullScore() {
        ExamAttempt a = new ExamAttempt();
        a.setScore(10);
        a.setTotal(10); // 10/10 = 100% → avg = 100.0

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a));

        var result = statsService.get("u1");
        assertEquals(1, result.get("attemptCount"));
        assertEquals(100.0, result.get("avgScore"));
    }

    @Test
    void avgScoreMultipleAttemptsAveraged() {
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(8); a1.setTotal(10); // 0.8
        ExamAttempt a2 = new ExamAttempt();
        a2.setScore(6); a2.setTotal(10); // 0.6
        ExamAttempt a3 = new ExamAttempt();
        a3.setScore(10); a3.setTotal(10); // 1.0
        // avg = (0.8+0.6+1.0)/3 = 0.8 → * 100 = 80.0

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2, a3));

        var result = statsService.get("u1");
        assertEquals(3, result.get("attemptCount"));
        assertEquals(80.0, result.get("avgScore"));
    }

    @Test
    void avgScoreRoundedToOneDecimal() {
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(1); a1.setTotal(3); // 0.3333...
        ExamAttempt a2 = new ExamAttempt();
        a2.setScore(2); a2.setTotal(3); // 0.6666...
        // avg = (0.3333+0.6666)/2 = 0.5 → *100 = 50.0 exactly (after rounding)

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2));

        double avg = (double) statsService.get("u1").get("avgScore");
        // result should be Math.round(50.0) = 50.0
        assertEquals(50.0, avg, 0.01);
    }

    @Test
    void avgScoreSkipsAttemptsWithZeroTotal() {
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(5); a1.setTotal(10); // 0.5, counted
        ExamAttempt a2 = new ExamAttempt();
        a2.setScore(100); a2.setTotal(0); // total=0 → skipped in sum

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2));

        var result = statsService.get("u1");
        assertEquals(2, result.get("attemptCount")); // count includes all
        // sum / attemptCount = 0.5 / 2 = 0.25 → avg = 25.0
        assertEquals(25.0, result.get("avgScore"));
    }

    @Test
    void avgScoreAttemptsNullReturnsZero() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(null);

        var result = statsService.get("u1");
        assertEquals(0, result.get("attemptCount"));
        assertEquals(0.0, result.get("avgScore"));
    }
}
