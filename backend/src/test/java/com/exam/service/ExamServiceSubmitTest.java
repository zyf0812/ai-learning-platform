package com.exam.service;

import com.exam.mapper.DocumentMapper;
import com.exam.mapper.ExamAttemptMapper;
import com.exam.mapper.ExamMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongQuestionMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceSubmitTest {

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
        examService = new ExamService(examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue);
    }

    @Test
    void submitScoresExactMatchesAndSkipsWrongQuestionBook() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "choice", "B", ""),
            question("q2", "fill", "Spring", ""),
            question("q3", "truefalse", "正确", "")));

        Map<String, Object> result = examService.submit("e1", "u1",
            Map.of("q1", "B", "q2", "Spring", "q3", "正确"));

        assertEquals(3, result.get("score"));
        assertEquals(3, result.get("total"));
        Map<String, Object> q1 = graded(result, "q1");
        assertEquals(true, q1.get("correct"));
        assertEquals(false, q1.get("isFuzzyMatch"));
        assertEquals(1.0, (Double) q1.get("hitRatio"), 0.0001);

        verify(wqMapper, never()).insert(any());
        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertEquals("e1", captor.getValue().getExamId());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals(3, captor.getValue().getScore());
        assertTrue(captor.getValue().getAnswers().contains("\"q1\""));
    }

    @Test
    void submitRecordsWrongAnswersInWrongQuestionBook() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "choice", "A", "")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(0, result.get("score"));
        assertEquals(false, graded(result, "q1").get("correct"));
        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wrong = captor.getValue();
        assertEquals("u1_q1", wrong.getId());
        assertEquals("u1", wrong.getUserId());
        assertEquals("q1", wrong.getQuestionId());
        assertEquals("e1", wrong.getExamId());
        assertEquals("B", wrong.getUserAnswer());
        assertEquals(false, wrong.getMastered());
        assertEquals(0, wrong.getReviewCount());
    }

    @Test
    void submitTreatsShortAnswerAsCorrectAtSixtyPercentKeywordCoverage() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "参考答案", "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "封装和继承"));

        assertEquals(1, result.get("score"));
        Map<String, Object> graded = graded(result, "q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(true, graded.get("isFuzzyMatch"));
        assertEquals(0.67, (Double) graded.get("hitRatio"), 0.0001);
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submitTreatsShortAnswerAsWrongBelowThreshold() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "参考答案", "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "封装"));

        assertEquals(0, result.get("score"));
        Map<String, Object> graded = graded(result, "q1");
        assertEquals(false, graded.get("correct"));
        assertEquals(false, graded.get("isFuzzyMatch"));
        assertEquals(0.33, (Double) graded.get("hitRatio"), 0.0001);
        verify(wqMapper).insert(any());
    }

    @Test
    void submitFallsBackToExactMatchWithoutKeywords() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "精确答案", "没有采分点标记的解析")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "精确答案"));

        assertEquals(1, result.get("score"));
        assertEquals(false, graded(result, "q1").get("isFuzzyMatch"));
        assertEquals(1.0, (Double) graded(result, "q1").get("hitRatio"), 0.0001);
    }

    @Test
    void submitMatchesShortKeywordByCharacterCoverage() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "参考答案", "解析【采分点】信息隐藏")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "数据隐藏"));

        assertEquals(1, result.get("score"));
        assertEquals(true, graded(result, "q1").get("correct"));
        assertEquals(false, graded(result, "q1").get("isFuzzyMatch"));
    }

    @Test
    void submitHandlesMissingAnswerAsWrong() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "choice", "A", "")));

        Map<String, Object> result = examService.submit("e1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        assertEquals("", graded(result, "q1").get("userAnswer"));
        verify(wqMapper).insert(any());
    }

    @Test
    void submitStillPersistsAttemptWhenWrongQuestionInsertFails() {
        when(questionMapper.findByExamId("e1"))
            .thenReturn(List.of(question("q1", "choice", "A", "")));
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        Map<String, Object> result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(0, result.get("score"));
        verify(attemptMapper).insert(any());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> graded(Map<String, Object> result, String questionId) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(questionId);
    }

    private static Question question(String id, String type, String answer, String explanation) {
        Question question = new Question();
        question.setId(id);
        question.setType(type);
        question.setContent("题目" + id);
        question.setAnswer(answer);
        question.setExplanation(explanation);
        return question;
    }
}
