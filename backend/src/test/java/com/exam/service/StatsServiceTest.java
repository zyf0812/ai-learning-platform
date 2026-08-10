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
import static org.mockito.Mockito.*;

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
    void getReturnsAllStatsForUser() {
        Document d = new Document();
        Exam e = new Exam();
        ExamAttempt a = new ExamAttempt();
        a.setScore(8);
        a.setTotal(10);

        when(docMapper.findByUserId("u1")).thenReturn(List.of(d));
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e));
        when(fcMapper.countByUserId("u1")).thenReturn(5);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a));

        var result = statsService.get("u1");

        assertEquals(1, result.get("documentCount"));
        assertEquals(1, result.get("examCount"));
        assertEquals(1, result.get("attemptCount"));
        assertEquals(80.0, result.get("avgScore"));
        assertEquals(5, result.get("flashcardCount"));
        assertEquals(0, result.get("wrongCount"));
    }

    @Test
    void getMultipleAttemptsCalculatesCorrectAverage() {
        ExamAttempt a1 = new ExamAttempt();
        a1.setScore(8); a1.setTotal(10);
        ExamAttempt a2 = new ExamAttempt();
        a2.setScore(5); a2.setTotal(5);

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a1, a2));

        var result = statsService.get("u1");

        assertEquals(2, result.get("attemptCount"));
        assertEquals(90.0, result.get("avgScore"));
    }

    @Test
    void getWithNoAttemptsReturnsZeroAverage() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        var result = statsService.get("u1");

        assertEquals(0, result.get("attemptCount"));
        assertEquals(0.0, result.get("avgScore"));
    }

    @Test
    void getWithMasteredWrongQuestionsExcludesThem() {
        WrongQuestion mastered = new WrongQuestion();
        mastered.setMastered(true);
        WrongQuestion notMastered = new WrongQuestion();
        notMastered.setMastered(false);

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(mastered, notMastered));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        var result = statsService.get("u1");

        assertEquals(1, result.get("wrongCount"));
    }

    @Test
    void getAttemptWithNullTotalHandledSafely() {
        ExamAttempt a = new ExamAttempt();
        a.setScore(8);

        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a));

        var result = statsService.get("u1");

        assertEquals(0.0, result.get("avgScore"));
    }
}