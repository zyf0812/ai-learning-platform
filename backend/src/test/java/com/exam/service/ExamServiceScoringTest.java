package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 覆盖 ExamService 中的评分逻辑（简答题采分点模糊匹配、精确匹配题型）、
 * parseKeywords 解析和 keywordMatch 字符覆盖匹配。
 * 这部分是业务核心评分算法，完全没有测试覆盖，风险极高。
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceScoringTest {

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
        examService = new ExamService(
            examMapper, questionMapper, docMapper, deepSeek, ragService,
            wqMapper, attemptMapper, taskQueue
        );
    }

    // ===== submit: choice exact match correct =====

    @Test
    void submitChoiceCorrectGivesScore() {
        Question q = makeQuestion("q1", "choice", "B", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("q1").get("correct"));
    }

    // ===== submit: choice exact match wrong =====

    @Test
    void submitChoiceWrongNoScoreAndWritesWrongQuestion() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "C"));

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any());
    }

    // ===== submit: fill exact match =====

    @Test
    void submitFillBlankExactMatch() {
        Question q = makeQuestion("q1", "fill", "Spring", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitFillBlankWithWhitespaceTrimmed() {
        // User answer has leading/trailing whitespace — service calls .trim()
        Question q = makeQuestion("q1", "fill", "Bean", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "  Bean  "));
        assertEquals(1, result.get("score"));
    }

    // ===== submit: truefalse exact match =====

    @Test
    void submitTrueFalseCorrect() {
        Question q = makeQuestion("q1", "truefalse", "正确", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var result = examService.submit("e1", "u1", Map.of("q1", "正确"));
        assertEquals(1, result.get("score"));
    }

    // ===== submit: shortanswer NO采分点 → fallback to exact match =====

    @Test
    void submitShortAnswerNoKeywordsFallsBackExact() {
        Question q = makeQuestion("q1", "shortanswer", "封装继承多态", "No markers here");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1", Map.of("q1", "封装继承多态"));
        assertEquals(1, r.get("score"));

        r = examService.submit("e1", "u1", Map.of("q1", "封装继承"));
        assertEquals(0, r.get("score"));
    }

    // ===== submit: shortanswer WITH采分点 — fuzzy threshold 0.6 =====

    @Test
    void submitShortAnswerAllKeywordsHitFullScore() {
        Question q = makeQuestion("q1", "shortanswer", "答案很长",
            "解析文字。【采分点】封装、继承、多态、抽象");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1",
            Map.of("q1", "面向对象的四大特性是封装、继承、多态和抽象"));

        assertEquals(1, r.get("score"));
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) ((Map<?, ?>) r.get("answers")).get("q1");
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerPartialHitAboveThresholdCorrect() {
        // 5 keywords, hit 3 → 0.6 ≥ 0.6 → correct
        Question q = makeQuestion("q1", "shortanswer", "参考答案",
            "【采分点】Spring、Bean、IOC、AOP、MVC");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1",
            Map.of("q1", "Spring 框架包含 Bean 管理和 IOC 容器"));

        assertEquals(1, r.get("score"));
        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) ((Map<?, ?>) r.get("answers")).get("q1");
        assertTrue((Boolean) g.get("isFuzzyMatch"));
        double ratio = (double) g.get("hitRatio");
        assertTrue(ratio >= 0.6 && ratio < 1.0, "Expected fuzzy but not perfect hit ratio, got " + ratio);
    }

    @Test
    void submitShortAnswerPartialHitBelowThresholdWrong() {
        // 5 keywords, hit 2 → 0.4 < 0.6 → wrong
        Question q = makeQuestion("q1", "shortanswer", "参考答案",
            "【采分点】Spring、Bean、IOC、AOP、MVC");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1",
            Map.of("q1", "Spring 框架很流行"));

        assertEquals(0, r.get("score"));
    }

    @Test
    void submitShortAnswerHitRatioRoundedToTwoDecimals() {
        // 3 keywords. If 2 hit → 0.666..., rounded by Math.round(x*100)/100.0 → 0.67
        Question q = makeQuestion("q1", "shortanswer", "参考答案",
            "【采分点】CPU、内存、硬盘");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1",
            Map.of("q1", "计算机包括 CPU 和 内存 两大件"));

        @SuppressWarnings("unchecked")
        var g = (Map<String, Object>) ((Map<?, ?>) r.get("answers")).get("q1");
        double ratio = (double) g.get("hitRatio");
        // check it's a multiple of 0.01
        assertEquals(ratio, Math.round(ratio * 100) / 100.0, 0.0001);
    }

    // ===== submit:采分点 keywordMatch uses char-coverage for longer shared substrings =====

    @Test
    void submitKeywordCharCoveragePath() {
        // "数据隐藏" vs "信息隐藏" — share "隐藏". keywordMatch falls back to char coverage.
        // "数据隐藏" chars: {'数','据','隐','藏'} → 4 chars
        // user answer contains "隐藏" → 2 covered → 0.5 exactly, hits threshold
        Question q = makeQuestion("q1", "shortanswer", "参考答案",
            "【采分点】数据隐藏、封装");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1",
            Map.of("q1", "面向对象的封装体现了信息隐藏的思想"));
        assertEquals(1, r.get("score"));
    }

    // ===== submit:采分点 with various delimiters =====

    @Test
    void submitKeywordsParsedWithDifferentDelimiters() {
        Question q = makeQuestion("q1", "shortanswer", "参考答案",
            "【采分点】封装 继承,多态，抽象");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1",
            Map.of("q1", "封装 继承 多态 抽象"));
        assertEquals(1, r.get("score"));
    }

    // ===== submit:采分点 newlines stop parsing =====

    @Test
    void submitKeywordsStopAtNewlineAfterMarker() {
        // Only keywords on the same line as 【采分点】 are parsed
        Question q = makeQuestion("q1", "shortanswer", "参考答案",
            "解析\n【采分点】封装、继承\n这行不应被解析（多态、抽象）");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        // Answer has both, but only 2 keywords exist.
        // If "多态" or "抽象" were accidentally parsed, then a user answer missing them
        // would still get full score only if the parser correctly stopped at newline.
        var r = examService.submit("e1", "u1",
            Map.of("q1", "封装 继承"));
        assertEquals(1, r.get("score"));
    }

    // ===== submit: empty / missing user answer =====

    @Test
    void submitEmptyUserAnswerCountsAsWrong() {
        Question q = makeQuestion("q1", "fill", "Java", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1", Map.of());
        assertEquals(0, r.get("score"));
        verify(wqMapper).insert(any());
    }

    // ===== submit: attemptMapper insert is called =====

    @Test
    void submitPersistsExamAttempt() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));

        var r = examService.submit("e1", "u1", Map.of("q1", "A"));

        verify(attemptMapper).insert(any());
        assertNotNull(r.get("id"));
        assertEquals(1, r.get("score"));
        assertEquals(1, r.get("total"));
    }

    // ===== submit: duplicate WrongQuestion insert is caught and ignored =====

    @Test
    void submitDuplicateWrongQuestionInsertDoesNotFail() {
        Question q = makeQuestion("q1", "choice", "A", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        // Should not bubble up
        assertDoesNotThrow(() -> examService.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ===== permission & existence in other ExamService methods =====

    @Test
    void getWithUserIdThrowsIfNotOwner() {
        var exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("other");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.get("e1", "me"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void getWithUserIdNullExamReturnsNull() {
        when(examMapper.findById("nope")).thenReturn(null);
        assertNull(examService.get("nope", "me"));
    }

    @Test
    void deleteMissingThrows() {
        when(examMapper.findById("nope")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> examService.delete("nope", "me"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void deleteWrongOwnerThrows() {
        var exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("other");
        when(examMapper.findById("e1")).thenReturn(exam);

        var ex = assertThrows(RuntimeException.class, () -> examService.delete("e1", "me"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void deleteOwnerOkDelegates() {
        var exam = new com.exam.model.Exam();
        exam.setId("e1");
        exam.setUserId("me");
        when(examMapper.findById("e1")).thenReturn(exam);
        doNothing().when(examMapper).deleteById("e1");

        examService.delete("e1", "me");
        verify(examMapper).deleteById("e1");
    }

    // ===== helpers =====

    private Question makeQuestion(String id, String type, String answer, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setContent("示例题目内容");
        q.setOptions("[]");
        return q;
    }
}
