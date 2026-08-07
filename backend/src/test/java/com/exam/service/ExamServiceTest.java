package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
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
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Question mkQuestion(String id, String type, String answer) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent(type + " question " + id);
        q.setAnswer(answer);
        q.setExplanation("");
        return q;
    }

    private Question mkShortAnswer(String id, String answer, String explanationWithKeywords) {
        Question q = new Question();
        q.setId(id);
        q.setType("shortanswer");
        q.setContent("简答题 " + id);
        q.setAnswer(answer);
        q.setExplanation(explanationWithKeywords);
        return q;
    }

    // ══════════════════════════════════════════════════════════════════
    // Access control: get(id, userId) / delete(id, userId)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void getExamAsOwnerReturnsQuestions() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("alice");
        e.setTitle("midterm");
        e.setQuestionTypes("choice");
        e.setQuestionCount(2);
        when(examMapper.findById("e1")).thenReturn(e);
        List<Question> qs = List.of(mkQuestion("q1", "choice", "A"));
        when(questionMapper.findByExamId("e1")).thenReturn(qs);

        var result = examService.get("e1", "alice");
        assertNotNull(result);
        assertEquals("e1", result.get("id"));
        assertEquals("alice", result.get("userId"));
        assertEquals(qs, result.get("questions"));
    }

    @Test
    void getExamAsOtherUserThrowsForbidden() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("alice");
        when(examMapper.findById("e1")).thenReturn(e);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.get("e1", "bob"));
        assertTrue(ex.getMessage().contains("无权访问"));
    }

    @Test
    void getMissingExamReturnsNull() {
        when(examMapper.findById("nope")).thenReturn(null);
        assertNull(examService.get("nope", "alice"));
    }

    @Test
    void deleteExamAsOwnerSucceeds() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("alice");
        when(examMapper.findById("e1")).thenReturn(e);
        doNothing().when(examMapper).deleteById("e1");

        assertDoesNotThrow(() -> examService.delete("e1", "alice"));
        verify(examMapper).deleteById("e1");
    }

    @Test
    void deleteExamAsOtherUserThrows() {
        Exam e = new Exam();
        e.setId("e1");
        e.setUserId("alice");
        when(examMapper.findById("e1")).thenReturn(e);

        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("e1", "bob"));
        assertTrue(ex.getMessage().contains("无权删除"));
    }

    @Test
    void deleteMissingExamThrows() {
        when(examMapper.findById("ghost")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> examService.delete("ghost", "alice"));
        assertTrue(ex.getMessage().contains("考试不存在"));
    }

    // ══════════════════════════════════════════════════════════════════
    // submit(): objective questions (choice / fill / truefalse) — exact match
    // ══════════════════════════════════════════════════════════════════

    @Test
    void submitChoiceCorrectAnswerScoresPoint() {
        Question q = mkQuestion("q1", "choice", "B");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals("B", g.get("userAnswer"));
        assertEquals(1.0, g.get("hitRatio"));
    }

    @Test
    void submitChoiceWrongAnswerNoScoreAndRecordsWrong() {
        Question q = mkQuestion("q1", "choice", "A");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());
        doNothing().when(wqMapper).insert(any());

        Map<String, String> answers = Map.of("q1", "C");

        var result = examService.submit("e1", "u1", answers);

        assertEquals(0, result.get("score"));
        verify(wqMapper).insert(any());
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals("C", g.get("userAnswer"));
    }

    @Test
    void submitFillExactMatchScores() {
        Question q = mkQuestion("q1", "fill", "Spring");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of("q1", "Spring"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitFillCaseSensitiveNoScore() {
        Question q = mkQuestion("q1", "fill", "Spring");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());
        doNothing().when(wqMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of("q1", "spring"));
        assertEquals(0, result.get("score"));
    }

    @Test
    void submitTrueFalseCorrect() {
        Question q1 = mkQuestion("q1", "truefalse", "正确");
        Question q2 = mkQuestion("q2", "truefalse", "错误");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q1, q2));
        doNothing().when(attemptMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "正确");
        answers.put("q2", "错误");
        var result = examService.submit("e1", "u1", answers);
        assertEquals(2, result.get("score"));
    }

    @Test
    void submitAnswerWhitespaceIsTrimmed() {
        Question q = mkQuestion("q1", "choice", "D");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // leading + trailing spaces around "D"
        var result = examService.submit("e1", "u1", Map.of("q1", "  D  \t"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitMissingAnswerTreatedAsEmptyString() {
        Question q = mkQuestion("q1", "fill", "value");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());
        doNothing().when(wqMapper).insert(any());

        // answers map has no key "q1"
        var result = examService.submit("e1", "u1", new HashMap<>());
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertEquals("", g.get("userAnswer"));
    }

    @Test
    void submitAllWrongZeroScore() {
        List<Question> qs = List.of(
            mkQuestion("q1", "choice", "A"),
            mkQuestion("q2", "fill", "X"),
            mkQuestion("q3", "truefalse", "正确")
        );
        when(questionMapper.findByExamId("e1")).thenReturn(qs);
        doNothing().when(attemptMapper).insert(any());
        doNothing().when(wqMapper).insert(any());

        Map<String, String> answers = new HashMap<>();
        answers.put("q1", "B");
        answers.put("q2", "Y");
        answers.put("q3", "错误");
        var result = examService.submit("e1", "u1", answers);
        assertEquals(0, result.get("score"));
        assertEquals(3, result.get("total"));
    }

    // ══════════════════════════════════════════════════════════════════
    // submit(): short-answer fuzzy scoring via 【采分点】 keywords
    // ══════════════════════════════════════════════════════════════════

    @Test
    void submitShortAnswerAllKeywordsHitPerfectScore() {
        // 5 keywords, answer hits all → 100%
        Question q = mkShortAnswer("q1", "面向对象的三大特性是封装、继承、多态",
            "【采分点】封装、继承、多态");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        String userAns = "面向对象三大特性：封装 继承 多态，这是Java的基础。";
        var result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertEquals(1.0, g.get("hitRatio"));
        assertFalse((Boolean) g.get("isFuzzyMatch"));
    }

    @Test
    void submitShortAnswerAboveThresholdFuzzyMatch() {
        // 5 keywords → hitting 4 = 80% ≥ 60% → correct, fuzzy
        Question q = mkShortAnswer("q1", "标准答案",
            "【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // includes 封装, 继承, 多态, 抽象 → 4/5 = 0.8
        String userAns = "封装和继承是基础，多态很重要，抽象要理解";
        var result = examService.submit("e1", "u1", Map.of("q1", userAns));

        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertTrue((Boolean) g.get("correct"));
        assertTrue((Boolean) g.get("isFuzzyMatch"),
            "hitRatio 0.8 < 1.0 so should be fuzzy match, got: " + g.get("hitRatio"));
        assertEquals(0.8, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerExactlyAtThresholdPasses() {
        // 5 keywords → hitting 3 = 60% → exactly FUZZY_THRESHOLD
        Question q = mkShortAnswer("q1", "std",
            "【采分点】A、B、C、D、E");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of("q1", "A B C 都很重要"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerBelowThresholdFails() {
        // 5 keywords → hitting 2 = 40% < 60% → wrong
        Question q = mkShortAnswer("q1", "std",
            "【采分点】封装、继承、多态、抽象、接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());
        doNothing().when(wqMapper).insert(any());

        var result = examService.submit("e1", "u1", Map.of("q1", "封装 继承 其它无关"));
        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graded = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graded.get("q1");
        assertFalse((Boolean) g.get("correct"));
        assertEquals(0.4, g.get("hitRatio"));
    }

    @Test
    void submitShortAnswerNoKeywordsMarkerFallsBackExactMatch() {
        // explanation exists but has no 【采分点】 → exact compare
        Question q = mkShortAnswer("q1", "42", "答案是42因为宇宙终极问题。");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "42")).get("score"));
        assertEquals(0, examService.submit("e1", "u1", Map.of("q1", "四十二")).get("score"));
    }

    @Test
    void submitShortAnswerEmptyExplanationFallsBackExactMatch() {
        Question q = mkShortAnswer("q1", "OK", "");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        assertEquals(1, examService.submit("e1", "u1", Map.of("q1", "OK")).get("score"));
    }

    @Test
    void submitShortAnswerKeywordsSeparatedByCommaAndSpace() {
        // Mixed separators (comma, Chinese comma, space, 顿号) should all split
        Question q = mkShortAnswer("q1", "any",
            "【采分点】封装, 继承，多态 接口");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1",
            Map.of("q1", "封装 继承 多态 接口 全都答对了"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordsCutOffAtNewline() {
        // Any content on lines after 【采分点】 line should be ignored
        Question q = mkShortAnswer("q1", "any",
            "解析内容先写点\n【采分点】封装、继承、多态\n后续补充说明不要解析为关键词");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        var result = examService.submit("e1", "u1",
            Map.of("q1", "封装 继承 多态"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submitShortAnswerKeywordFuzzyCharacterCoverage() {
        // keywordMatch supports partial char coverage for synonyms
        // e.g. keyword "数据隐藏" vs user answer containing "信息隐藏" shares 隐藏
        // keyword.length() <= 6 is required for this branch
        Question q = mkShortAnswer("q1", "any",
            "【采分点】数据隐藏");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(q));
        doNothing().when(attemptMapper).insert(any());

        // "信息隐藏" contains "隐藏" (2 of 4 chars) = 50% → ≥0.5 → match
        var result = examService.submit("e1", "u1",
            Map.of("q1", "面向对象的特点有信息隐藏、封装等"));
        assertEquals(1, result.get("score"));
    }

    // ══════════════════════════════════════════════════════════════════
    // list()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void listReturnsUserExamsFromMapper() {
        List<Exam> fake = new ArrayList<>();
        Exam e = new Exam(); e.setId("e1"); e.setTitle("Math");
        fake.add(e);
        when(examMapper.findByUserId("u1")).thenReturn(fake);

        var result = examService.list("u1");
        assertSame(fake, result);
        assertEquals(1, result.size());
        assertEquals("Math", result.get(0).getTitle());
    }
}
