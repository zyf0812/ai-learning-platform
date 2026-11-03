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

/**
 * 考试判分是业务关键链路：精确匹配、简答题采分点模糊匹配阈值、错题入库、成绩持久化。
 * 这些行为此前完全没有被测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private RAGService ragService;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private TaskQueueService taskQueue;

    private ExamService service;

    @BeforeEach
    void setUp() {
        service = new ExamService(examMapper, questionMapper, docMapper, deepSeek,
            ragService, wqMapper, attemptMapper, taskQueue);
    }

    private static Question question(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("e1");
        q.setType(type);
        q.setContent("题目 " + id);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        return q;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gradedFor(Map<String, Object> result, String questionId) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get(questionId);
    }

    @Test
    void choiceAnswersMatchAfterTrimmingWhitespace() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(question("q1", "choice", "B", "解析")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "  B  "));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
        assertEquals(false, g.get("isFuzzyMatch"));
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void wrongChoiceIsRecordedInWrongQuestionBook() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(question("q1", "choice", "A", "解析")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "C"));

        assertEquals(0, result.get("score"));
        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q1", wq.getQuestionId());
        assertEquals("e1", wq.getExamId());
        assertEquals("C", wq.getUserAnswer());
        assertEquals(false, wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void shortAnswerAbove60PercentKeywordsIsFuzzyCorrect() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "标准答案", "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "封装和继承"));

        assertEquals(1, result.get("score"));
        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(true, g.get("isFuzzyMatch"));
        assertEquals(0.67, (Double) g.get("hitRatio"), 0.001);
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void shortAnswerBelowThresholdIsIncorrectAndRecorded() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "标准答案", "解析【采分点】封装、继承、多态")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "封装"));

        assertEquals(0, result.get("score"));
        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(false, g.get("correct"));
        assertEquals(0.33, (Double) g.get("hitRatio"), 0.001);
        verify(wqMapper).insert(any());
    }

    @Test
    void shortAnswerAtExactly60PercentIsAccepted() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "标准答案", "【采分点】封装、继承、多态、抽象、接口")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "封装 继承 多态"));

        assertEquals(1, result.get("score"));
        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(0.6, (Double) g.get("hitRatio"), 0.001, "阈值边界应为包含（>= 60%）");
    }

    @Test
    void shortAnswerWithoutMarkersFallsBackToExactMatch() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "参考答案", "普通解析")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "参考答案"));

        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void longKeywordsRequireFullSubstringMatch() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "标准答案", "【采分点】数据结构与算法")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "结构与算法"));

        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(false, g.get("correct"), "超过6字的关键词不应走字符覆盖率兜底");
        assertEquals(0.0, g.get("hitRatio"));
    }

    @Test
    void keywordParsingSupportsMixedSeparators() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "标准答案", "【采分点】A、B，C D")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "A B C D"));

        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(true, g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
        assertEquals(false, g.get("isFuzzyMatch"));
    }

    @Test
    void keywordsStopAtNewline() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "shortanswer", "标准答案", "【采分点】封装、继承\n后续说明")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "封装 后续说明"));

        Map<String, Object> g = gradedFor(result, "q1");
        assertEquals(false, g.get("correct"));
        assertEquals(0.5, (Double) g.get("hitRatio"), 0.001, "换行后的说明不应被当作采分点");
    }

    @Test
    void attemptIsPersistedWithScoreAndGradedAnswers() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(
            question("q1", "choice", "A", "解析"),
            question("q2", "fill", "Spring", "解析")));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "A", "q2", "Java"));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        ExamAttempt attempt = captor.getValue();
        assertEquals("e1", attempt.getExamId());
        assertEquals("u1", attempt.getUserId());
        assertEquals(1, attempt.getScore());
        assertEquals(2, attempt.getTotal());
        assertNotNull(attempt.getAnswers());
        assertTrue(attempt.getAnswers().contains("\"q1\""), attempt.getAnswers());
        assertTrue(attempt.getAnswers().contains("\"q2\""), attempt.getAnswers());
    }

    @Test
    void emptyExamGradesZeroWithoutFailing() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of());

        Map<String, Object> result = service.submit("e1", "u1", Map.of());

        assertEquals(0, result.get("score"));
        assertEquals(0, result.get("total"));
        verify(attemptMapper).insert(any());
        verify(wqMapper, never()).insert(any());
    }

    @Test
    void wrongQuestionInsertFailureDoesNotLoseAttempt() {
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(question("q1", "choice", "A", "解析")));
        doThrow(new RuntimeException("db down")).when(wqMapper).insert(any());

        assertDoesNotThrow(() -> service.submit("e1", "u1", Map.of("q1", "B")));
        verify(attemptMapper).insert(any());
    }
}
