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
class ExamServiceTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private RAGService ragService;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private TaskQueueService taskQueue;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper, deepSeek,
                ragService, wqMapper, attemptMapper, taskQueue);
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

    @Test
    void submitGradesExactMatchAndPersistsAttempt() {
        Question q1 = question("q1", "choice", "B", "");
        Question q2 = question("q2", "choice", "A", "");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q1, q2));

        Map<String, Object> result = examService.submit("exam1", "user1",
                Map.of("q1", " B ", "q2", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(2, result.get("total"));

        Map<?, ?> graded = (Map<?, ?>) result.get("answers");
        assertEquals(true, ((Map<?, ?>) graded.get("q1")).get("correct"));
        assertEquals("B", ((Map<?, ?>) graded.get("q1")).get("userAnswer"));
        assertEquals(false, ((Map<?, ?>) graded.get("q2")).get("correct"));

        verify(wqMapper, times(1)).insert(any(WrongQuestion.class));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getScore());
        assertEquals(2, captor.getValue().getTotal());
        assertEquals("user1", captor.getValue().getUserId());
    }

    @Test
    void shortAnswerPassesWhenKeywordHitRatioReachesThreshold() {
        Question q = question("q1", "shortanswer", "封装、继承、多态",
                "解析【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("exam1", "user1",
                Map.of("q1", "封装和继承"));

        assertEquals(1, result.get("score"));
        Map<?, ?> graded = (Map<?, ?>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(true, graded.get("isFuzzyMatch"));
        assertEquals(0.67, graded.get("hitRatio"));
        verify(wqMapper, never()).insert(any(WrongQuestion.class));
    }

    @Test
    void shortAnswerFailsWhenKeywordHitRatioBelowThreshold() {
        Question q = question("q1", "shortanswer", "封装、继承、多态",
                "解析【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("exam1", "user1",
                Map.of("q1", "只提到了封装"));

        assertEquals(0, result.get("score"));
        Map<?, ?> graded = (Map<?, ?>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(false, graded.get("correct"));
        assertEquals(false, graded.get("isFuzzyMatch"));
        assertEquals(0.33, graded.get("hitRatio"));
        verify(wqMapper, times(1)).insert(any(WrongQuestion.class));
    }

    @Test
    void shortAnswerFallsBackToExactMatchWithoutScoringKeywords() {
        Question q = question("q1", "shortanswer", "Spring", "普通解析，没有采分点标记");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("exam1", "user1",
                Map.of("q1", "Spring"));

        assertEquals(1, result.get("score"));
        Map<?, ?> graded = (Map<?, ?>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(true, graded.get("correct"));
        assertEquals(1.0, graded.get("hitRatio"));
    }

    @Test
    void shortAnswerMatchesKeywordByCharacterCoverage() {
        Question q = question("q1", "shortanswer", "多态性",
                "解析【采分点】多态性");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, Object> result = examService.submit("exam1", "user1",
                Map.of("q1", "多态"));

        assertEquals(1, result.get("score"));
        Map<?, ?> graded = (Map<?, ?>) ((Map<?, ?>) result.get("answers")).get("q1");
        assertEquals(true, graded.get("correct"));
    }

    @Test
    void submitWithNoQuestionsReturnsZeroScore() {
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of());

        Map<String, Object> result = examService.submit("exam1", "user1", Map.of());

        assertEquals(0, result.get("score"));
        assertEquals(0, result.get("total"));
    }
}
