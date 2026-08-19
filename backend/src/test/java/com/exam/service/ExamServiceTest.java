package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock
    private ExamMapper examMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private DocumentMapper docMapper;
    @Mock
    private DeepSeekService deepSeek;
    @Mock
    private RAGService ragService;
    @Mock
    private WrongQuestionMapper wqMapper;
    @Mock
    private ExamAttemptMapper attemptMapper;
    @Mock
    private TaskQueueService taskQueue;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // --- Permission / access control tests ---

    @Test
    void getExamWithWrongUserThrowsPermissionError() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner123");
        exam.setTitle("Test Exam");
        exam.setQuestionTypes("choice");
        exam.setQuestionCount(3);

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "intruder456"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getExamWithCorrectUserReturnsData() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner123");
        exam.setTitle("Test Exam");
        exam.setQuestionTypes("choice");
        exam.setQuestionCount(1);

        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("What is 2+2?");
        q.setAnswer("B");

        when(examMapper.findById("e1")).thenReturn(exam);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.get("e1", "owner123");
        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("owner123", result.get("userId"));
        @SuppressWarnings("unchecked")
        List<Question> qs = (List<Question>) result.get("questions");
        assertEquals(1, qs.size());
        assertEquals("q1", qs.get(0).getId());
    }

    @Test
    void deleteNonExistentExamThrows() {
        when(examMapper.findById("nonexistent")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("nonexistent", "user1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteExamWithWrongUserThrowsPermissionError() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "other"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteExamWithCorrectUserSucceeds() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");

        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }

    // --- Exam submit: choice / fill / truefalse exact match ---

    @Test
    void submitChoiceQuestionCorrectGetsPoint() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setContent("2+2?");
        q.setAnswer("B");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, String> answers = Map.of("q1", "B");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals(true, gq1.get("correct"));
        assertEquals("B", gq1.get("correctAnswer"));
    }

    @Test
    void submitChoiceQuestionWrongNoPoint() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        Map<String, String> answers = Map.of("q1", "C");
        Map<String, Object> result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void submitFillBlankExactMatchRequired() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("Spring");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // Exact match
        Map<String, Object> r1 = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, r1.get("score"));

        // Wrong case or extra whitespace trimmed
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        Map<String, Object> r2 = examService.submit("e1", "u1", Map.of("q1", "spring"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submitTrueFalseQuestion() {
        Question q = new Question();
        q.setId("q1");
        q.setType("truefalse");
        q.setAnswer("正确");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, Object> r1 = examService.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, r1.get("score"));

        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        Map<String, Object> r2 = examService.submit("e1", "u1", Map.of("q1", "错误"));
        assertEquals(0, r2.get("score"));
    }

    // --- Submit: Short answer fuzzy matching via 【采分点】 ---

    @Test
    void submitShortAnswerNoMarkersFallsBackToExact() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java是面向对象语言");
        q.setExplanation("标准解释无关键词标记");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // Exact match
        Map<String, Object> r1 = examService.submit("e1", "u1",
            Map.of("q1", "Java是面向对象语言"));
        assertEquals(1, r1.get("score"));

        // Different answer without fuzzy markers -> exact match fails
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));
        Map<String, Object> r2 = examService.submit("e1", "u1",
            Map.of("q1", "Java面向对象"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submitShortAnswerWithKeywordsFullHit() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("Java支持封装、继承、多态");
        q.setExplanation("面向对象三大特性。\n【采分点】封装、继承、多态");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "Java的三大特性是封装、继承和多态"));
        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        // hitRatio = 3/3 = 1.0, not fuzzy
        assertEquals(1.0, gq1.get("hitRatio"));
        assertEquals(false, gq1.get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerFuzzyAboveThreshold() {
        // 5 keywords -> need >= 60% = 3 hits to pass
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象的特性");
        q.setExplanation("【采分点】封装、继承、多态、抽象、接口");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // 3 out of 5 keywords = 0.6 exactly at threshold
        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "这个知识点涉及封装、还有继承和多态特性"));
        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals(true, gq1.get("correct"));
        assertEquals(true, gq1.get("isFuzzyMatch"));
        assertEquals(0.6, gq1.get("hitRatio"));
    }

    @Test
    void submitShortAnswerFuzzyBelowThreshold() {
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("面向对象特性描述");
        q.setExplanation("【采分点】封装、继承、多态、抽象、接口");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        // 2 out of 5 = 0.4 < 0.6 threshold
        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "我只记得封装和继承两个词"));
        assertEquals(0, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals(false, gq1.get("correct"));
        assertEquals(0.4, gq1.get("hitRatio"));
    }

    @Test
    void submitShortAnswerKeywordsParsedUpToNewline() {
        // Content after \n should not be parsed as keywords
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("OOP特性");
        q.setExplanation("【采分点】封装、继承、多态\n这是额外的解释文字，不应算入关键词。");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // Only 3 keywords (not extra text), hit 2 = 0.666 pass
        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "答案包含封装和继承两个关键词"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordMatchByCoverage() {
        // keywordMatch uses character coverage for longish keywords (len <= 6)
        Question q = new Question();
        q.setId("q1");
        q.setType("shortanswer");
        q.setAnswer("数据隐藏概念");
        q.setExplanation("【采分点】数据隐藏");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        // "信息隐藏" vs "数据隐藏" share "隐藏" (2/4 chars = 50% exactly at threshold)
        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "面向对象中信息隐藏很重要"));
        assertEquals(1, result.get("score"));
    }

    // --- Wrong question book behavior ---

    @Test
    void submitWrongAnswerCreatesWrongQuestionRecord() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        examService.submit("e1", "u99", Map.of("q1", "wrong"));

        verify(wqMapper).insert(argThat(wq ->
            "u99_q1".equals(wq.getId())
                && "u99".equals(wq.getUserId())
                && "q1".equals(wq.getQuestionId())
                && "e1".equals(wq.getExamId())
                && Boolean.FALSE.equals(wq.getMastered())
                && Integer.valueOf(0).equals(wq.getReviewCount())
        ));
    }

    @Test
    void submitDuplicateWrongQuestionDoesNotPropagateError() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));
        // Simulate unique constraint violation (duplicate)
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any(WrongQuestion.class));

        // Should not propagate - submit should still return result
        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "wrong"));
        assertEquals(0, result.get("score"));
    }

    // --- Edge cases: empty answers, missing questions ---

    @Test
    void submitMissingAnswerTreatedAsEmptyString() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));
        doNothing().when(wqMapper).insert(any(WrongQuestion.class));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of());
        assertEquals(0, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> gq1 = (Map<String, Object>) graded.get("q1");
        assertEquals("", gq1.get("userAnswer"));
    }

    @Test
    void submitWhitespaceAnswerIsTrimmed() {
        Question q = new Question();
        q.setId("q1");
        q.setType("fill");
        q.setAnswer("correct");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "  correct  "));
        assertEquals(1, result.get("score"));
    }

    // --- ExamAttempt persistence edge cases ---

    @Test
    void submitAttemptInsertFailureStillReturnsResult() {
        Question q = new Question();
        q.setId("q1");
        q.setType("choice");
        q.setAnswer("A");

        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("db down")).when(attemptMapper).insert(any(ExamAttempt.class));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "A"));
        assertEquals(1, result.get("score"));
        assertNotNull(result.get("id"));
    }

    // --- List exams ---

    @Test
    void listExamsDelegatesToMapper() {
        Exam e1 = new Exam(); e1.setId("e1"); e1.setTitle("Math");
        Exam e2 = new Exam(); e2.setId("e2"); e2.setTitle("History");
        when(examMapper.findByUserId("u1")).thenReturn(List.of(e1, e2));

        List<Exam> exams = examService.list("u1");
        assertEquals(2, exams.size());
        assertEquals("Math", exams.get(0).getTitle());
    }
}
