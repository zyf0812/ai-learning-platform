package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.WrongQuestionMapper;
import com.exam.model.Document;
import com.exam.model.Exam;
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
import static org.mockito.Mockito.*;

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

    private WrongQuestion wrong(boolean mastered) {
        WrongQuestion wq = new WrongQuestion();
        wq.setMastered(mastered);
        return wq;
    }

    private ExamAttempt attempt(int score, int total) {
        ExamAttempt a = new ExamAttempt();
        a.setScore(score);
        a.setTotal(total);
        return a;
    }

    @Test
    void aggregatesCountsAndAverageScore() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of(new Document(), new Document()));
        when(examMapper.findByUserId("u1")).thenReturn(List.of(new Exam()));
        when(fcMapper.countByUserId("u1")).thenReturn(7);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of(wrong(true), wrong(false)));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(attempt(8, 10), attempt(6, 10)));

        Map<String, Object> stats = service.get("u1");

        assertEquals(2, stats.get("documentCount"));
        assertEquals(1, stats.get("examCount"));
        assertEquals(7, stats.get("flashcardCount"));
        assertEquals(1, stats.get("wrongCount"));
        assertEquals(2, stats.get("attemptCount"));
        assertEquals(70.0, stats.get("avgScore"));
    }

    @Test
    void noAttemptsYieldsZeroAverage() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> stats = service.get("u1");

        assertEquals(0, stats.get("attemptCount"));
        assertEquals(0.0, stats.get("avgScore"));
    }

    @Test
    void attemptWithZeroTotalIsCountedWithoutDividingByZero() {
        when(docMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(fcMapper.countByUserId("u1")).thenReturn(0);
        when(wqMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(attempt(10, 10), attempt(0, 0)));

        Map<String, Object> stats = service.get("u1");

        assertEquals(2, stats.get("attemptCount"));
        assertEquals(50.0, stats.get("avgScore"));
    }
}
