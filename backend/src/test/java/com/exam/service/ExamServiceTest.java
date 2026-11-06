package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongQuestionMapper;
import com.exam.model.Exam;
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

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper documentMapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private RAGService ragService;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private TaskQueueService taskQueue;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, documentMapper, deepSeek,
            ragService, wqMapper, attemptMapper, taskQueue);
    }

    private Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gradedFor(Map<String, Object> result, String questionId) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(questionId);
    }

    @Test
    void exactChoiceMatchScoresOneAndSkipsWrongQuestionBook() {
        Question q = question("q1", "choice", "B", "explanation");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        Map<String, Object> graded = gradedFor(result, "q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(1.0, graded.get("hitRatio"));
        assertEquals(false, graded.get("isFuzzyMatch"));

        verify(wqMapper, never()).insert(any(WrongQuestion.class));
        ArgumentCaptor<com.exam.model.ExamAttempt> attempt =
            ArgumentCaptor.forClass(com.exam.model.ExamAttempt.class);
        verify(attemptMapper).insert(attempt.capture());
        assertEquals(1, attempt.getValue().getScore());
        assertEquals(1, attempt.getValue().getTotal());
        assertEquals("e1", attempt.getValue().getExamId());
        assertTrue(attempt.getValue().getAnswers().contains("q1"));
    }

    @Test
    void answerIsTrimmedBeforeComparison() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "truefalse", "正确", null)));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "  正确  "));

        assertEquals(1, result.get("score"));
        assertEquals("正确", gradedFor(result, "q1").get("userAnswer"));
    }

    @Test
    void choiceGradingIsCaseSensitive() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "choice", "B", null)));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "b"));

        assertEquals(0, result.get("score"));
        assertEquals(false, gradedFor(result, "q1").get("correct"));
    }

    @Test
    void shortAnswerAtSixtyPercentKeywordThresholdIsCorrectAndFuzzy() {
        // 2 of 3 keywords hit => 0.66 >= 0.6 threshold
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案",
                "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "封装和继承是核心"));

        assertEquals(1, result.get("score"));
        Map<String, Object> graded = gradedFor(result, "q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(true, graded.get("isFuzzyMatch"));
        assertEquals(0.67, graded.get("hitRatio"));
        verify(wqMapper, never()).insert(any(WrongQuestion.class));
    }

    @Test
    void shortAnswerBelowThresholdIsWrongAndEnteredIntoWrongQuestionBook() {
        // only 1 of 3 keywords hit => 0.33 < 0.6
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案",
                "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "只提到封装"));

        assertEquals(0, result.get("score"));
        assertEquals(false, gradedFor(result, "q1").get("correct"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        assertEquals("u1_q1", captor.getValue().getId());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals("q1", captor.getValue().getQuestionId());
        assertEquals("e1", captor.getValue().getExamId());
        assertEquals("只提到封装", captor.getValue().getUserAnswer());
        assertEquals(false, captor.getValue().getMastered());
    }

    @Test
    void shortAnswerWithoutScoringMarkerFallsBackToExactMatch() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "shortanswer", "Spring", "没有采分点标记")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "Spring"));

        assertEquals(1, result.get("score"));
        assertEquals(false, gradedFor(result, "q1").get("isFuzzyMatch"));
    }

    @Test
    void keywordMatchingIsCaseInsensitive() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案",
                "解析【采分点】JVM、GC")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "jvm 和 gc"));

        assertEquals(1, result.get("score"));
        assertEquals(1.0, gradedFor(result, "q1").get("hitRatio"));
    }

    @Test
    void shortKeywordMatchesOnCharacterCoverageForSynonyms() {
        // "数据隐藏" shares 隐藏 with "信息隐藏" => coverage 0.5 => match
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "shortanswer", "参考答案",
                "解析【采分点】数据隐藏、封装、继承")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "信息隐藏和封装"));

        assertEquals(1, result.get("score"));
        Map<String, Object> graded = gradedFor(result, "q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(0.67, graded.get("hitRatio"));
    }

    @Test
    void unansweredQuestionsAreGradedWrongWithoutFailure() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "choice", "A", null), question("q2", "fill", "Spring", null)));

        Map<String, Object> result = examService.submit("e1", "u1", new HashMap<>());

        assertEquals(0, result.get("score"));
        assertEquals(2, result.get("total"));
        assertEquals("", gradedFor(result, "q1").get("userAnswer"));
        verify(wqMapper, times(2)).insert(any(WrongQuestion.class));
    }

    @Test
    void getRejectsExamOwnedByAnotherUser() {
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "intruder"));
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
        exam.setId("e1");
        exam.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "intruder"));
        assertEquals("无权删除该考试", ex.getMessage());
        verify(examMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteMissingExamFails() {
        when(examMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> examService.delete("missing", "u1"));
        assertEquals("考试不存在", ex.getMessage());
    }
}
