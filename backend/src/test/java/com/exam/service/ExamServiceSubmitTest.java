package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
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

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DocumentChunkMapper chunkMapper;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;

    private ExamService service;

    @BeforeEach
    void setUp() {
        // TaskQueueService/RAGService/DeepSeekService are CONCRETE CLASSES that cannot be
        // mocked by Mockito inline mock maker on JDK 25. They are also UNUSED by the
        // submit/get/delete code paths under test, so we pass minimal placeholder instances.
        TaskQueueService taskQueue = new TaskQueueService(null);
        DeepSeekService deepSeek = new DeepSeekService(null, null, null);
        RAGService ragService = new RAGService(chunkMapper, null, null, null, 1024);

        service = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    private Question makeQuestion(String id, String type, String content,
                                  String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setOptions("[]");
        return q;
    }

    // ==================== submit: choice ====================

    @Test
    void submitChoiceCorrectAnswerScores() {
        Question q = makeQuestion("q1", "choice", "2+2?", "B", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, r.get("score"));
        assertEquals(1, r.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> answers = (Map<String, Map<String, Object>>) r.get("answers");
        assertTrue((Boolean) answers.get("q1").get("correct"));
        assertEquals("B", answers.get("q1").get("correctAnswer"));
    }

    @Test
    void submitChoiceWrongAnswerCreatesWrongQuestion() {
        Question q = makeQuestion("q1", "choice", "Capital of France?", "A", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        service.submit("e1", "u1", Map.of("q1", "B"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        WrongQuestion wq = captor.getValue();
        assertEquals("u1_q1", wq.getId());
        assertEquals("u1", wq.getUserId());
        assertEquals("q1", wq.getQuestionId());
        assertEquals("B", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void submitChoiceWrongAnswerIsCaseSensitive() {
        Question q = makeQuestion("q1", "choice", "?", "B", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "b"));
        assertEquals(0, r.get("score"));
    }

    @Test
    void submitChoiceAnswerTrimmedBeforeCompare() {
        Question q = makeQuestion("q1", "choice", "?", "C", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "  C  "));
        assertEquals(1, r.get("score"));
    }

    // ==================== submit: fill ====================

    @Test
    void submitFillBlankCorrectMatches() {
        Question q = makeQuestion("q1", "fill", "___ is the capital of Japan.", "Tokyo", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "Tokyo"));
        assertEquals(1, r.get("score"));
    }

    @Test
    void submitFillBlankWrongAnswer() {
        Question q = makeQuestion("q1", "fill", "?", "Kyoto", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "Tokyo"));
        assertEquals(0, r.get("score"));
    }

    // ==================== submit: truefalse ====================

    @Test
    void submitTrueFalseCorrect() {
        Question q = makeQuestion("q1", "truefalse", "Water boils at 100C.", "正确", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, r.get("score"));
    }

    @Test
    void submitTrueFalseIncorrect() {
        Question q = makeQuestion("q1", "truefalse", "Sun orbits Earth.", "错误", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(0, r.get("score"));
    }

    // ==================== submit: shortanswer w/ fuzzy ====================

    @Test
    void submitShortAnswerExactMatchScore1() {
        Question q = makeQuestion("q1", "shortanswer", "Explain OOP.",
            "Encapsulation inheritance polymorphism abstraction",
            "Standard def.【采分点】封装、继承、多态、抽象");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q1", "封装 继承 多态 抽象"));
        assertEquals(1, r.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> answers = (Map<String, Map<String, Object>>) r.get("answers");
        assertEquals(1.0, answers.get("q1").get("hitRatio"));
        assertFalse((Boolean) answers.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswer60PercentHitIsCorrectFuzzy() {
        Question q = makeQuestion("q1", "shortanswer", "?",
            "Ref answer",
            "【采分点】封装、继承、多态、抽象、组合");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q1", "代码使用了封装 继承 多态特性"));
        assertEquals(1, r.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> answers = (Map<String, Map<String, Object>>) r.get("answers");
        double ratio = (Double) answers.get("q1").get("hitRatio");
        assertTrue(ratio >= 0.6 && ratio < 1.0, "Expected fuzzy hit ratio >=0.6, got " + ratio);
        assertTrue((Boolean) answers.get("q1").get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerBelow60PercentIsIncorrect() {
        Question q = makeQuestion("q1", "shortanswer", "?",
            "Ref",
            "【采分点】封装、继承、多态、抽象、组合");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q1", "这个设计里有封装和继承"));
        assertEquals(0, r.get("score"));
    }

    @Test
    void submitShortAnswerNoKeywordsMarkerFallsBackExactMatch() {
        Question q = makeQuestion("q1", "shortanswer", "?",
            "ExactAnswerHere",
            "just plain explanation no采分点 marker");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        assertEquals(1, service.submit("e1", "u1",
            Map.of("q1", "ExactAnswerHere")).get("score"));
        assertEquals(0, service.submit("e1", "u1",
            Map.of("q1", "Different")).get("score"));
    }

    @Test
    void submitShortAnswerKeywordsOnlyUpToNewline() {
        // Use non-overlapping Chinese keywords so fuzzy character-coverage matching
        // (keywordMatch method) does not falsely count a 2nd keyword as hit when
        // it shares characters with the first one (e.g. 关键词1 vs 关键词2).
        Question q = makeQuestion("q1", "shortanswer", "?",
            "A",
            "【采分点】苹果、香蕉\n后续内容不应被解析、不应成为关键词");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        assertEquals(1, service.submit("e1", "u1",
            Map.of("q1", "这里包含苹果和香蕉")).get("score"));
        // 2 keywords, only 1 hit (no shared characters) → ratio 0.5 < threshold 0.6 → wrong
        assertEquals(0, service.submit("e1", "u1",
            Map.of("q1", "我只吃苹果")).get("score"));
    }

    @Test
    void submitShortAnswerEmptyAnswerZeroScore() {
        Question q = makeQuestion("q1", "shortanswer", "?", "A",
            "【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        assertEquals(0, service.submit("e1", "u1", Map.of()).get("score"));
    }

    // ==================== submit: hitRatio rounding ====================

    @Test
    void submitHitRatioRoundedToTwoDecimals() {
        Question q = makeQuestion("q1", "shortanswer", "?", "ref",
            "【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1",
            Map.of("q1", "封装 继承"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> answers = (Map<String, Map<String, Object>>) r.get("answers");
        double ratio = (Double) answers.get("q1").get("hitRatio");
        assertEquals(0.67, ratio, 0.001);
    }

    // ==================== submit: persistence ====================

    @Test
    void submitPersistsExamAttempt() {
        Question q = makeQuestion("q1", "choice", "?", "A", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> r = service.submit("e1", "u1", Map.of("q1", "A"));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        ExamAttempt attempt = captor.getValue();
        assertEquals(r.get("id"), attempt.getId());
        assertEquals("e1", attempt.getExamId());
        assertEquals("u1", attempt.getUserId());
        assertEquals(1, attempt.getScore());
        assertEquals(1, attempt.getTotal());
    }

    // ==================== submit: wrong question insert already covered but also duplicate ignore ====================

    @Test
    void submitWrongQuestionInsertExceptionIsIgnored() {
        Question q = makeQuestion("q1", "choice", "?", "A", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        assertDoesNotThrow(() -> service.submit("e1", "u1", Map.of("q1", "B")));
    }

    @Test
    void submitExamAttemptInsertExceptionIsLoggedNotThrown() {
        Question q = makeQuestion("q1", "choice", "?", "A", null);
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("db down")).when(attemptMapper).insert(any());

        assertDoesNotThrow(() -> service.submit("e1", "u1", Map.of("q1", "A")));
    }

    // ==================== Exam get / delete auth checks ====================

    @Test
    void getByIdWithUserIdRejectsOtherUser() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(e);

        assertThrows(RuntimeException.class, () -> service.get("e1", "other-user"));
    }

    @Test
    void getByIdWithNullReturnsNull() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertNull(service.get("missing", "u1"));
    }

    @Test
    void deleteRejectsNonOwner() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(e);

        assertThrows(RuntimeException.class, () -> service.delete("e1", "other"));
    }

    @Test
    void deleteNonExistentThrows() {
        when(examMapper.findById("missing")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.delete("missing", "u1"));
    }

    @Test
    void deleteOwnerSucceeds() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("owner");
        when(examMapper.findById("e1")).thenReturn(e);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> service.delete("e1", "owner"));
        verify(examMapper).deleteById("e1");
    }
}