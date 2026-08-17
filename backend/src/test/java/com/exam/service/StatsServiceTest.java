package com.exam.service;

import com.exam.mapper.*;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock private DocumentMapper documentMapper;
    @Mock private ExamMapper examMapper;
    @Mock private FlashCardMapper flashCardMapper;
    @Mock private WrongQuestionMapper wrongQuestionMapper;
    @Mock private ExamAttemptMapper attemptMapper;

    private StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(documentMapper, examMapper, flashCardMapper, wrongQuestionMapper, attemptMapper);
    }

    private ExamAttempt makeAttempt(int score, int total) {
        ExamAttempt a = new ExamAttempt();
        a.setScore(score);
        a.setTotal(total);
        return a;
    }

    private WrongQuestion makeWQ(String id, boolean mastered) {
        WrongQuestion w = new WrongQuestion();
        w.setId(id);
        w.setMastered(mastered);
        return w;
    }

    // ==================== 基本计数 ====================

    @Test
    void getCountsAllCategories() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of(new Document(), new Document()));
        when(examMapper.findByUserId("u1")).thenReturn(List.of(new Exam()));
        when(flashCardMapper.countByUserId("u1")).thenReturn(15);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> r = service.get("u1");

        assertEquals(2, r.get("documentCount"));
        assertEquals(1, r.get("examCount"));
        assertEquals(15, r.get("flashcardCount"));
        assertEquals(0, r.get("wrongCount"));
        assertEquals(0, r.get("attemptCount"));
        assertEquals(0.0, r.get("avgScore"));
    }

    // ==================== 均分计算：正常场景 ====================

    @Test
    void getAvgScoreSingleAttempt() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(
            makeAttempt(8, 10) // 0.8 → 80.0%
        ));

        Map<String, Object> r = service.get("u1");

        assertEquals(1, r.get("attemptCount"));
        // Math.round(0.8 / 1 * 1000) / 10.0 = 800 / 10.0 = 80.0
        assertEquals(80.0, (Double) r.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreMultipleAttemptsAveraged() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(
            makeAttempt(10, 10),   // 1.0
            makeAttempt(5, 10),    // 0.5
            makeAttempt(0, 10)     // 0.0
        )); // 平均 1.5/3 = 0.5 → 50.0%

        Map<String, Object> r = service.get("u1");

        assertEquals(3, r.get("attemptCount"));
        assertEquals(50.0, (Double) r.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreRoundsTo1Decimal() {
        // 3次考试：1/3=0.333... → 平均0.333... * 1000 = 333.333 → round → 333 / 10.0 = 33.3
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(
            makeAttempt(1, 3),
            makeAttempt(1, 3),
            makeAttempt(1, 3)
        )); // 总和: 0.333... → round(333.333) = 333 → 33.3

        Map<String, Object> r = service.get("u1");

        assertEquals(33.3, (Double) r.get("avgScore"), 0.001);
    }

    // ==================== 均分计算：除零 / 空值保护 ====================

    @Test
    void getAvgScoreEmptyAttemptsReturnsZero() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> r = service.get("u1");

        assertEquals(0, r.get("attemptCount"));
        assertEquals(0.0, r.get("avgScore"));
    }

    @Test
    void getAvgScoreNullAttemptsReturnsZero() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(null);

        Map<String, Object> r = service.get("u1");

        assertEquals(0, r.get("attemptCount"));
        assertEquals(0.0, r.get("avgScore"));
    }

    @Test
    void getAvgScoreTotalZeroSkipsToAvoidDivisionByZero() {
        // total=0 的记录跳过求和（防止除零），但 attemptCount 仍计入总记录数
        ExamAttempt broken = makeAttempt(5, 0); // total=0 → 不参与 sum
        ExamAttempt good = makeAttempt(8, 10);  // 0.8
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(broken, good));

        Map<String, Object> r = service.get("u1");

        assertEquals(2, r.get("attemptCount"));
        // sum = 0.8, attemptCount = 2 → 0.8/2 = 0.4 → 40.0
        assertEquals(40.0, (Double) r.get("avgScore"), 0.001);
    }

    @Test
    void getAvgScoreTotalNullSkips() {
        ExamAttempt a = new ExamAttempt();
        a.setScore(10);
        a.setTotal(null); // null → 跳过求和
        ExamAttempt good = makeAttempt(6, 10); // 0.6
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of(a, good));

        Map<String, Object> r = service.get("u1");

        // sum = 0.6, attemptCount = 2 → 0.6/2 = 0.3 → 30.0
        assertEquals(30.0, (Double) r.get("avgScore"), 0.001);
    }

    // ==================== 错题统计：只统计未掌握 ====================

    @Test
    void wrongCountCountsOnlyUnmastered() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of(
            makeWQ("w1", false),  // 未掌握 → 计数
            makeWQ("w2", false),  // 未掌握 → 计数
            makeWQ("w3", true)    // 已掌握 → 不计
        ));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> r = service.get("u1");

        assertEquals(2, r.get("wrongCount"));
    }

    @Test
    void wrongCountAllMasteredIsZero() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of(
            makeWQ("w1", true),
            makeWQ("w2", true)
        ));
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> r = service.get("u1");

        assertEquals(0, r.get("wrongCount"));
    }

    @Test
    void wrongCountEmptyListIsZero() {
        when(documentMapper.findByUserId("u1")).thenReturn(List.of());
        when(examMapper.findByUserId("u1")).thenReturn(List.of());
        when(flashCardMapper.countByUserId("u1")).thenReturn(0);
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of());
        when(attemptMapper.findByUserId("u1")).thenReturn(List.of());

        Map<String, Object> r = service.get("u1");

        assertEquals(0, r.get("wrongCount"));
    }
}
