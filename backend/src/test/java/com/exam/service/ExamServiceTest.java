package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Targeted coverage for ExamService.submit(...) — the short-answer grading path
 * is intricate (keyword extraction + ratio threshold + character coverage fallback)
 * and previously had zero test coverage, so a regression here would silently
 * corrupt the wrong-question book and user scores.
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
        service = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue);
    }

    private Question q(String id, String type, String answer, String explanation) {
        Question question = new Question();
        question.setId(id);
        question.setExamId("e1");
        question.setType(type);
        question.setContent("content-" + id);
        question.setAnswer(answer);
        question.setExplanation(explanation);
        return question;
    }

    @Test
    void choiceQuestionIsExactMatch() {
        Question q = q("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "B"));
        assertEquals(1, result.get("score"));
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        Map<String, Object> entry = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) entry.get("correct"));
        assertEquals(1.0, entry.get("hitRatio"));
        assertFalse((Boolean) entry.get("isFuzzyMatch"));
        verifyNoInteractions(wqMapper);
    }

    @Test
    void wrongChoiceWritesToWrongQuestionBook() {
        Question q = q("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, Object> result = service.submit("e1", "u1", Map.of("q1", "C"));
        assertEquals(0, result.get("score"));
        verify(wqMapper, times(1)).insert(any());
    }

    @Test
    void fillQuestionIsExactMatch() {
        Question q = q("q1", "fill", "Spring", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = service.submit("e1", "u1", Map.of("q1", " Spring "));
        assertEquals(1, result.get("score"));
    }

    @Test
    void truefalseQuestionIsExactMatch() {
        Question q = q("q1", "truefalse", "正确", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = service.submit("e1", "u1", Map.of("q1", "错误"));
        assertEquals(0, result.get("score"));
    }

    @Test
    void shortAnswerAboveThresholdCountsAsCorrect() {
        Question q = q("q1", "shortanswer", "Spring",
            "Spring是Java框架。【采分点】Java、框架、依赖注入");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = service.submit("e1", "u1",
            Map.of("q1", "Java生态下的主流框架，核心是依赖注入"));

        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        assertTrue((Boolean) entry.get("correct"));
        // 3/3 keywords hit → hitRatio = 1.0 → not a fuzzy match
        assertEquals(1.0, entry.get("hitRatio"));
        assertFalse((Boolean) entry.get("isFuzzyMatch"));
    }

    @Test
    void shortAnswerAtSixtyPercentThresholdIsCorrect() {
        Question q = q("q1", "shortanswer", "Spring",
            "Spring。【采分点】Java、框架、依赖注入、IoC、AOP");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // Hits "Java" + "框架" + "依赖注入" + "IoC" = 4/5 = 0.8 ≥ 0.6 → correct but partial.
        var result = service.submit("e1", "u1",
            Map.of("q1", "Java 语言写的框架，提供依赖注入和 IoC 容器"));
        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        assertTrue((Boolean) entry.get("correct"));
        assertTrue((Boolean) entry.get("isFuzzyMatch"),
            "partial keyword hit (above threshold but <100%) must be marked isFuzzyMatch");
        assertEquals(0.8, entry.get("hitRatio"));
    }

    @Test
    void shortAnswerBelowThresholdIsIncorrect() {
        Question q = q("q1", "shortanswer", "Spring",
            "Spring。【采分点】Java、框架、依赖注入、IoC、AOP");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // No keyword hits → 0/5 = 0.0 < 0.6 → wrong
        var result = service.submit("e1", "u1", Map.of("q1", "随便写点东西"));
        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        assertFalse((Boolean) entry.get("correct"));
        assertEquals(0.0, entry.get("hitRatio"));
        verify(wqMapper, times(1)).insert(any());
    }

    @Test
    void shortAnswerFallsBackToExactMatchWhenNoKeywordMarker() {
        // No 【采分点】 marker → must compare against answer field directly.
        Question q = q("q1", "shortanswer", "Spring",
            "Spring是Java框架（无采分点标记）");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var exact = service.submit("e1", "u1", Map.of("q1", "Spring"));
        Map<String, Object> exactEntry = (Map<String, Object>)
            ((Map<String, Object>) exact.get("answers")).get("q1");
        assertTrue((Boolean) exactEntry.get("correct"));
        assertEquals(1.0, exactEntry.get("hitRatio"));

        var wrong = service.submit("e1", "u1", Map.of("q1", "Spring Boot"));
        Map<String, Object> wrongEntry = (Map<String, Object>)
            ((Map<String, Object>) wrong.get("answers")).get("q1");
        assertFalse((Boolean) wrongEntry.get("correct"));
    }

    @Test
    void keywordCharacterCoverageAppliesToShortKeywords() {
        Question q = q("q1", "shortanswer", "隐藏",
            "封装。【采分点】隐藏");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // "隐藏" length = 2 ≤ 6 → character-coverage fallback can rescue partial answers.
        // User says "数据信息隐藏" — contains "隐藏" verbatim → direct hit.
        var ok = service.submit("e1", "u1", Map.of("q1", "数据信息隐藏"));
        Map<String, Object> okEntry = (Map<String, Object>)
            ((Map<String, Object>) ok.get("answers")).get("q1");
        assertTrue((Boolean) okEntry.get("correct"));
    }

    @Test
    void longKeywordRequiresDirectSubstringPresence() {
        // Keyword length 7 > 6 disables the character-coverage fallback.
        Question q = q("q1", "shortanswer", "Spring",
            "【采分点】abcdefghijk"); // 11 chars
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = service.submit("e1", "u1", Map.of("q1", "bcdefg"));
        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        assertFalse((Boolean) entry.get("correct"),
            "long keywords must NOT benefit from the coverage fallback");
    }

    @Test
    void keywordParsingHandlesSeparatorVariations() {
        Question q = q("q1", "shortanswer", "Spring",
            "Spring介绍。【采分点】Java、框架，依赖注入 IoC");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = service.submit("e1", "u1",
            Map.of("q1", "Java 框架 依赖注入 IoC容器"));
        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        // All 4 keywords hit → 4/4 = 1.0
        assertTrue((Boolean) entry.get("correct"));
        assertEquals(1.0, entry.get("hitRatio"));
    }

    @Test
    void keywordParsingStopsAtNewline() {
        // Anything past the first newline in the tail must NOT be treated as keywords.
        Question q = q("q1", "shortanswer", "Spring",
            "Spring。【采分点】Java\n（后续内容）SpringBoot、框架");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = service.submit("e1", "u1", Map.of("q1", "Java"));
        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        assertTrue((Boolean) entry.get("correct"));
        assertEquals(1.0, entry.get("hitRatio"),
            "only 'Java' should be parsed; subsequent content must be ignored");
    }

    @Test
    void missingAnswerIsCountedAsWrong() {
        Question q = q("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        Map<String, String> submitted = new HashMap<>(); // no answer for q1
        var result = service.submit("e1", "u1", submitted);
        assertEquals(0, result.get("score"));
        Map<String, Object> entry = (Map<String, Object>)
            ((Map<String, Object>) result.get("answers")).get("q1");
        assertEquals("", entry.get("userAnswer"));
        assertFalse((Boolean) entry.get("correct"));
        verify(wqMapper, times(1)).insert(any());
    }

    @Test
    void blankExplanationShortAnswerFallsBackToExactMatch() {
        Question q = q("q1", "shortanswer", "Spring", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var ok = service.submit("e1", "u1", Map.of("q1", "Spring"));
        Map<String, Object> okEntry = (Map<String, Object>)
            ((Map<String, Object>) ok.get("answers")).get("q1");
        assertTrue((Boolean) okEntry.get("correct"));

        var bad = service.submit("e1", "u1", Map.of("q1", "SpringBoot"));
        Map<String, Object> badEntry = (Map<String, Object>)
            ((Map<String, Object>) bad.get("answers")).get("q1");
        assertFalse((Boolean) badEntry.get("correct"));
    }

    @Test
    void wrongQuestionInsertFailureDoesNotBreakGrading() {
        Question q = q("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("DB down")).when(wqMapper).insert(any());

        // Should NOT propagate the DB error to the caller — grading result is still returned.
        var result = service.submit("e1", "u1", Map.of("q1", "C"));
        assertEquals(0, result.get("score"));
        assertEquals(1, ((Map<?, ?>) result.get("answers")).size());
    }

    @Test
    void mixedQuestionTypesAreScoredTogether() {
        Question q1 = q("q1", "choice", "B", "");
        Question q2 = q("q2", "shortanswer", "Spring",
            "Spring。【采分点】Java、框架");
        Question q3 = q("q3", "truefalse", "正确", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2, q3));

        var result = service.submit("e1", "u1", Map.of(
            "q1", "B",                      // correct
            "q2", "Java 框架",              // correct
            "q3", "错误"                    // wrong
        ));
        assertEquals(2, result.get("score"));
        assertEquals(3, result.get("total"));
        verify(wqMapper, times(1)).insert(any());
    }

    @Test
    void examAttemptRecordIsPersistedEvenWhenScoreIsZero() {
        Question q = q("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        service.submit("e1", "u1", Map.of("q1", "C"));
        verify(attemptMapper, times(1)).insert(any());
    }
}