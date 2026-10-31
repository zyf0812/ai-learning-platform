package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongQuestionMapper;
import com.exam.model.Exam;
import com.exam.model.ExamAttempt;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 考试判分是面向用户的核心业务流程，其中简答题按采分点模糊判分分支此前没有任何测试。
 * 同时覆盖考试资源归属校验，防止越权读取/删除。
 */
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
    private WrongQuestionMapper wrongQuestionMapper;
    @Mock
    private ExamAttemptMapper attemptMapper;
    @Mock
    private TaskQueueService taskQueue;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper, deepSeek,
                ragService, wrongQuestionMapper, attemptMapper, taskQueue);
    }

    private static Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam-1");
        q.setType(type);
        q.setContent("题目" + id);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> graded(Map<String, Object> result, String questionId) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(questionId);
    }

    @Test
    void submitChoiceExactMatchScoresFullMarks() {
        when(questionMapper.findByExamId("exam-1"))
            .thenReturn(List.of(question("q1", "choice", "B", "解析")));

        Map<String, Object> result = examService.submit("exam-1", "user-1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        assertTrue((Boolean) graded(result, "q1").get("correct"));
        verify(wrongQuestionMapper, never()).insert(any());

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getScore());
        assertEquals(1, captor.getValue().getTotal());
        assertEquals("user-1", captor.getValue().getUserId());
    }

    @Test
    void submitWrongChoiceScoresZeroAndRecordsWrongQuestion() {
        when(questionMapper.findByExamId("exam-1"))
            .thenReturn(List.of(question("q1", "choice", "B", "解析")));

        Map<String, Object> result = examService.submit("exam-1", "user-1", Map.of("q1", "A"));

        assertEquals(0, result.get("score"));
        assertFalse((Boolean) graded(result, "q1").get("correct"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wrongQuestionMapper).insert(captor.capture());
        assertEquals("user-1_q1", captor.getValue().getId());
        assertEquals("A", captor.getValue().getUserAnswer());
        assertFalse(captor.getValue().getMastered());
    }

    @Test
    void submitShortAnswerWithHitRatioAtThresholdIsCorrect() {
        when(questionMapper.findByExamId("exam-1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案", "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = examService.submit("exam-1", "user-1", Map.of("q1", "封装和继承"));

        Map<String, Object> g = graded(result, "q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        assertEquals(0.67, (Double) g.get("hitRatio"), 0.0001);
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerBelowThresholdIsIncorrect() {
        when(questionMapper.findByExamId("exam-1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案", "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = examService.submit("exam-1", "user-1", Map.of("q1", "封装"));

        Map<String, Object> g = graded(result, "q1");
        assertFalse((Boolean) g.get("correct"));
        assertFalse((Boolean) g.get("isFuzzyMatch"));
        assertEquals(0.33, (Double) g.get("hitRatio"), 0.0001);
        assertEquals(0, result.get("score"));
    }

    @Test
    void submitShortAnswerWithoutMarkersFallsBackToExactMatch() {
        when(questionMapper.findByExamId("exam-1")).thenReturn(List.of(
            question("q1", "shortanswer", "参考答案", "普通解析"),
            question("q2", "shortanswer", "另一个答案", "普通解析")));

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "参考答案");
        answers.put("q2", "不相干的回答");
        Map<String, Object> result = examService.submit("exam-1", "user-1", answers);

        assertEquals(1, result.get("score"));
        assertTrue((Boolean) graded(result, "q1").get("correct"));
        assertFalse((Boolean) graded(result, "q2").get("correct"));
        assertFalse((Boolean) graded(result, "q2").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerMatchesKeywordByCharacterCoverage() {
        when(questionMapper.findByExamId("exam-1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案", "解析【采分点】数据隐藏")));

        Map<String, Object> result = examService.submit("exam-1", "user-1", Map.of("q1", "信息隐藏"));

        Map<String, Object> g = graded(result, "q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, (Double) g.get("hitRatio"), 0.0001);
        assertFalse((Boolean) g.get("isFuzzyMatch"));
    }

    @Test
    void submitMissingAnswerIsIncorrect() {
        when(questionMapper.findByExamId("exam-1"))
            .thenReturn(List.of(question("q1", "choice", "A", "解析")));

        Map<String, Object> result = examService.submit("exam-1", "user-1", Map.of());

        assertEquals(0, result.get("score"));
        assertFalse((Boolean) graded(result, "q1").get("correct"));
        assertEquals("", graded(result, "q1").get("userAnswer"));
        verify(wrongQuestionMapper).insert(any());
    }

    @Test
    void getRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setUserId("owner");
        when(examMapper.findById("exam-1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.get("exam-1", "intruder"));
        assertEquals("无权访问该考试", ex.getMessage());
        verify(questionMapper, never()).findByExamId(anyString());
    }

    @Test
    void getReturnsNullForMissingExam() {
        when(examMapper.findById("missing")).thenReturn(null);

        assertNull(examService.get("missing", "user-1"));
    }

    @Test
    void deleteRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setUserId("owner");
        when(examMapper.findById("exam-1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("exam-1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteMissingExamThrows() {
        when(examMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "user-1"));
        assertEquals("考试不存在", ex.getMessage());
    }
}
