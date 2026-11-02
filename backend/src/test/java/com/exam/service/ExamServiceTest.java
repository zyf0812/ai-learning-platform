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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 判分是核心业务链路：简答题采分点命中率、错误答案写错题本、考试记录持久化。
 * 这些逻辑此前完全没有测试，阈值和边界最容易在调整时回归。
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock
    private ExamMapper examMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private DocumentMapper documentMapper;
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
        examService = new ExamService(examMapper, questionMapper, documentMapper,
            deepSeek, ragService, wrongQuestionMapper, attemptMapper, taskQueue);
    }

    private Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gradeOf(Map<String, Object> result, String questionId) {
        Map<String, Object> grades = (Map<String, Object>) result.get("answers");
        return (Map<String, Object>) grades.get(questionId);
    }

    @Test
    void submitGradesExactAndFuzzyAnswersAndRecordsWrongQuestions() {
        List<Question> questions = List.of(
            question("q1", "choice", "B", "解析"),
            question("q2", "fill", "Spring", "解析"),
            question("q3", "shortanswer", "封装、继承、多态", "解析【采分点】封装、继承、多态"),
            question("q4", "shortanswer", "哈希表、红黑树、链表", "解析【采分点】哈希表、红黑树、链表"),
            question("q5", "shortanswer", "abc", "没有采分点标记")
        );
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, String> answers = Map.of(
            "q1", "B",
            "q2", "spring",
            "q3", "封装和继承",
            "q4", "链表",
            "q5", "abc"
        );

        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(3, result.get("score"));
        assertEquals(5, result.get("total"));

        Map<String, Object> g1 = gradeOf(result, "q1");
        assertEquals(true, g1.get("correct"));
        assertEquals(false, g1.get("isFuzzyMatch"));
        assertEquals(1.0, g1.get("hitRatio"));

        Map<String, Object> g2 = gradeOf(result, "q2");
        assertEquals(false, g2.get("correct"));
        assertEquals(0.0, g2.get("hitRatio"));

        Map<String, Object> g3 = gradeOf(result, "q3");
        assertEquals(true, g3.get("correct"));
        assertEquals(true, g3.get("isFuzzyMatch"));
        assertEquals(0.67, g3.get("hitRatio"));

        Map<String, Object> g4 = gradeOf(result, "q4");
        assertEquals(false, g4.get("correct"));
        assertEquals(false, g4.get("isFuzzyMatch"));
        assertEquals(0.33, g4.get("hitRatio"));

        Map<String, Object> g5 = gradeOf(result, "q5");
        assertEquals(true, g5.get("correct"));
        assertEquals(false, g5.get("isFuzzyMatch"));

        ArgumentCaptor<WrongQuestion> wrongCaptor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wrongQuestionMapper, times(2)).insert(wrongCaptor.capture());
        assertEquals("u1_q2", wrongCaptor.getAllValues().get(0).getId());
        assertEquals("u1_q4", wrongCaptor.getAllValues().get(1).getId());
        assertEquals(false, wrongCaptor.getAllValues().get(0).getMastered());

        ArgumentCaptor<ExamAttempt> attemptCaptor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(attemptCaptor.capture());
        assertEquals(3, attemptCaptor.getValue().getScore());
        assertEquals(5, attemptCaptor.getValue().getTotal());
        assertEquals("exam1", attemptCaptor.getValue().getExamId());
        assertEquals("u1", attemptCaptor.getValue().getUserId());
    }

    @Test
    void shortAnswerThresholdIsInclusiveAtSixtyPercent() {
        List<Question> questions = List.of(
            question("q6", "shortanswer", "参考", "解析【采分点】a、b、c、d、e"),
            question("q7", "shortanswer", "参考", "解析【采分点】a、b、c、d、e")
        );
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, String> answers = Map.of("q6", "abc", "q7", "ab");

        Map<String, Object> result = examService.submit("exam1", "u1", answers);

        assertEquals(1, result.get("score"));

        Map<String, Object> g6 = gradeOf(result, "q6");
        assertEquals(true, g6.get("correct"));
        assertEquals(true, g6.get("isFuzzyMatch"));
        assertEquals(0.6, g6.get("hitRatio"));

        Map<String, Object> g7 = gradeOf(result, "q7");
        assertEquals(false, g7.get("correct"));
        assertEquals(0.4, g7.get("hitRatio"));
    }

    @Test
    void emptyAnswerMapYieldsZeroScoreAndMarkedAttempt() {
        List<Question> questions = List.of(question("q1", "choice", "B", "解析"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, Object> result = examService.submit("exam1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        assertEquals(1, result.get("total"));

        ArgumentCaptor<ExamAttempt> attemptCaptor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(attemptCaptor.capture());
        assertEquals(0, attemptCaptor.getValue().getScore());
        verify(wrongQuestionMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void getRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("owner");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.get("exam1", "other"));

        assertEquals("无权访问该考试", ex.getMessage());
        verify(questionMapper, never()).findByExamId(anyString());
    }

    @Test
    void getReturnsNullForMissingExam() {
        when(examMapper.findById("missing")).thenReturn(null);

        assertNull(examService.get("missing", "u1"));
    }

    @Test
    void deleteRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("owner");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("exam1", "other"));

        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteOwnExamDelegatesToMapper() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("u1");
        when(examMapper.findById("exam1")).thenReturn(exam);

        examService.delete("exam1", "u1");

        verify(examMapper).deleteById("exam1");
    }
}
